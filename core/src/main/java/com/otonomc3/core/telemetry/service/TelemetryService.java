package com.otonomc3.core.telemetry.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.otonomc3.core.config.TelemetryProperties;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import com.otonomc3.core.telemetry.persistence.TelemetryKey;
import com.otonomc3.core.telemetry.persistence.TelemetryEntity;
import com.otonomc3.core.telemetry.persistence.TelemetryRepository;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates and stores telemetry batches.
 *
 * <p>Processing is idempotent: the same message may arrive more than once
 * (the edge adapter delivers "at least once", and a failed batch is redelivered),
 * but each (droneId, sessionId, seq) is stored only once.
 */
@Slf4j
@Service
public class TelemetryService {

	private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

	private final TelemetryRepository repository;
	private final TelemetryStats stats;
	private final Duration lateThreshold;

	/** Highest seq seen per adapter session of each drone. Kept in memory only. */
	private final Map<SessionKey, Long> lastSeq = new ConcurrentHashMap<>();

	public TelemetryService(TelemetryRepository repository, TelemetryStats stats, TelemetryProperties properties) {
		this.repository = repository;
		this.stats = stats;
		this.lateThreshold = properties.lateThreshold();
	}

	/**
	 * Stores a batch of messages in one transaction.
	 *
	 * @param messages   parsed messages, in the order they were received
	 * @param receivedAt when the batch was received; used for the late flag
	 */
	@Transactional
	public void process(List<TelemetryMessageDto> messages, Instant receivedAt) {
		List<TelemetryMessageDto> valid = messages.stream().filter(this::isValid).toList();

		// Sorted by seq so that reordering inside one batch is not reported as a gap.
		valid.stream().sorted(Comparator.comparing(TelemetryMessageDto::seq)).forEach(this::trackSeq);

		// 1. Duplicates inside the batch: keep the first copy.
		Map<TelemetryKey, TelemetryMessageDto> unique = new LinkedHashMap<>();
		for (TelemetryMessageDto message : valid) {
			unique.putIfAbsent(keyOf(message), message);
		}
		int duplicates = valid.size() - unique.size();

		// 2. Duplicates already in the database, found with one query.
		Set<TelemetryKey> existing = unique.isEmpty() ? Set.of() : repository.findExistingKeys(unique.keySet());
		duplicates += existing.size();

		List<TelemetryEntity> records = new ArrayList<>(unique.size());
		for (Map.Entry<TelemetryKey, TelemetryMessageDto> entry : unique.entrySet()) {
			if (!existing.contains(entry.getKey())) {
				records.add(toRecord(entry.getValue(), receivedAt));
			}
		}
		if (!records.isEmpty()) {
			// Flush now so database errors surface here, before the counters are updated.
			repository.saveAllAndFlush(records);
		}

		stats.addSaved(records.size());
		stats.addDuplicates(duplicates);
		stats.addLate(records.stream().filter(TelemetryEntity::isLate).count());
	}

	private boolean isValid(TelemetryMessageDto message) {
		if (message.droneId() == null || message.droneId() <= 0
				|| message.sessionId() == null
				|| message.timestamp() == null
				|| message.seq() == null) {
			log.warn("Skipping invalid telemetry: droneId={}, sessionId={}, seq={}, timestamp={}",
					message.droneId(), message.sessionId(), message.seq(), message.timestamp());
			return false;
		}
		return true;
	}

	/**
	 * Remembers the highest seq of the message's session and logs a WARN when
	 * messages were skipped.
	 *
	 * @return the number of missing messages before this one, 0 when there is no gap
	 */
	long trackSeq(TelemetryMessageDto message) {
		SessionKey key = new SessionKey(message.droneId(), message.sessionId());
		long seq = message.seq();
		Long last = lastSeq.get(key);
		if (last != null && seq <= last) {
			// Duplicate or out-of-order message, not a gap.
			return 0;
		}
		lastSeq.put(key, seq);
		if (last == null || seq == last + 1) {
			return 0;
		}
		long missing = seq - last - 1;
		log.warn("Seq gap for drone {} session {}: {} message(s) missing between seq {} and {}",
				message.droneId(), message.sessionId(), missing, last, seq);
		return missing;
	}

	boolean isLate(TelemetryMessageDto message, Instant receivedAt) {
		return Duration.between(message.timestamp(), receivedAt).compareTo(lateThreshold) > 0;
	}

	private static TelemetryKey keyOf(TelemetryMessageDto message) {
		return new TelemetryKey(message.droneId(), message.sessionId(), message.seq());
	}

	private TelemetryEntity toRecord(TelemetryMessageDto message, Instant receivedAt) {
		TelemetryEntity record = new TelemetryEntity();
		record.setDroneId(message.droneId());
		record.setSessionId(message.sessionId());
		record.setSeq(message.seq());
		record.setTimestamp(message.timestamp());
		record.setReceivedAt(receivedAt);
		record.setLate(isLate(message, receivedAt));

		TelemetryMessageDto.Position position = message.position();
		if (position != null) {
			record.setPosition(toPoint(position.lat(), position.lon()));
			record.setAltMsl(position.altMsl());
			record.setAltRel(position.altRel());
			record.setHeading(position.heading());
		}
		TelemetryMessageDto.Velocity velocity = message.velocity();
		if (velocity != null) {
			record.setGroundSpeed(velocity.groundSpeed());
			record.setAirSpeed(velocity.airSpeed());
			record.setClimbRate(velocity.climbRate());
		}
		TelemetryMessageDto.Battery battery = message.battery();
		if (battery != null) {
			record.setBatteryPercent(battery.percent());
			record.setBatteryVoltage(battery.voltage());
		}
		TelemetryMessageDto.Gps gps = message.gps();
		if (gps != null) {
			record.setGpsFixType(gps.fixType());
			record.setGpsSatellites(gps.satellites());
		}
		TelemetryMessageDto.Status status = message.status();
		if (status != null) {
			record.setMode(status.mode());
			record.setArmed(status.armed());
		}
		return record;
	}

	/** x = longitude, y = latitude, as PostGIS expects. Null unless both are known. */
	private static Point toPoint(Double lat, Double lon) {
		if (lat == null || lon == null) {
			return null;
		}
		return WGS84.createPoint(new Coordinate(lon, lat));
	}

	private record SessionKey(int droneId, UUID sessionId) {
	}

}
