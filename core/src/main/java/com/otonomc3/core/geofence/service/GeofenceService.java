package com.otonomc3.core.geofence.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.otonomc3.core.config.WebSocketConfig;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureCollectionDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureDto;
import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import org.locationtech.jts.geom.Polygon;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Stores the operators' no-fly zones and sends the full, current FeatureCollection
 * to /topic/geofences after every change, and also when a zone becomes effective or
 * stops being effective because of its validFrom/validUntil (checked every
 * otonom.geofence.effectiveness-check-interval).
 *
 * <p>PERMANENT zones are only changed by {@link GeofenceImporter}; changing or
 * deleting one here throws {@link PermanentGeofenceException}.
 *
 * <p>Every repository call runs in its own transaction, so the collection is only
 * sent after the change is committed. Every send is also published as a
 * {@link GeofencesChangedEvent}, so the onboard fences (FenceSyncService) follow.
 */
@Service
public class GeofenceService {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/geofences";

	private static final Set<String> UPDATABLE_FIELDS = Set.of("active", "validFrom", "validUntil");

	private final GeofenceRepository repository;
	private final SimpMessageSendingOperations messagingTemplate;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	/** Ids of the effective zones as last sent; null before the first check. Guarded by this. */
	private List<Long> lastEffectiveIds;

	public GeofenceService(GeofenceRepository repository, SimpMessageSendingOperations messagingTemplate,
			ApplicationEventPublisher eventPublisher, Clock clock) {
		this.repository = repository;
		this.messagingTemplate = messagingTemplate;
		this.eventPublisher = eventPublisher;
		this.clock = clock;
	}

	/**
	 * All zones, effective or not, ordered by id.
	 *
	 * @param at the time properties.effective is computed for
	 */
	public GeofenceFeatureCollectionDto findAll(Instant at) {
		return GeofenceFeatureCollectionDto.of(repository.findAllByOrderByIdAsc().stream()
				.map(entity -> GeofenceFeatureDto.from(entity, at))
				.toList());
	}

	/**
	 * Creates an active operator zone.
	 *
	 * @param kind TEMPORARY or MISSION; null means TEMPORARY
	 * @throws InvalidGeofenceException when the kind is PERMANENT, validUntil is not after validFrom,
	 *                                  or the polygon is malformed, not valid (ST_IsValid) or larger than 50 km²
	 */
	public GeofenceFeatureDto create(String name, GeoJsonPolygonDto geoJson, GeofenceKind kind, Instant validFrom,
			Instant validUntil) {
		if (kind == GeofenceKind.PERMANENT) {
			throw new InvalidGeofenceException("kind must be TEMPORARY or MISSION; PERMANENT zones are only imported");
		}
		checkValidity(validFrom, validUntil);
		Polygon polygon = GeofenceValidator.toPolygon(geoJson);
		checkInDatabase(polygon);

		GeofenceEntity entity = new GeofenceEntity();
		entity.setName(name.trim());
		entity.setArea(polygon);
		entity.setActive(true);
		entity.setKind(kind == null ? GeofenceKind.TEMPORARY : kind);
		entity.setSource(GeofenceSource.OPERATOR);
		entity.setValidFrom(validFrom);
		entity.setValidUntil(validUntil);
		Instant now = clock.instant();
		entity.setCreatedAt(now);
		GeofenceFeatureDto created = GeofenceFeatureDto.from(repository.save(entity), now);
		publish();
		return created;
	}

	/**
	 * Changes the given fields: active (true/false), validFrom and validUntil (an
	 * ISO 8601 time, or null to remove the bound). Fields left out stay as they are.
	 *
	 * @throws GeofenceNotFoundException  when the zone does not exist
	 * @throws PermanentGeofenceException when the zone is PERMANENT
	 * @throws InvalidGeofenceException   when a field is unknown or has a wrong value, no field is
	 *                                    given, or validUntil would not be after validFrom
	 */
	public GeofenceFeatureDto update(long id, Map<String, Object> changes) {
		GeofenceEntity entity = loadChangeable(id);
		if (changes == null || changes.isEmpty()) {
			throw new InvalidGeofenceException("at least one of active, validFrom, validUntil is required");
		}
		for (String field : changes.keySet()) {
			if (!UPDATABLE_FIELDS.contains(field)) {
				throw new InvalidGeofenceException("unknown field " + field + "; only active, validFrom and validUntil "
						+ "can be changed");
			}
		}
		if (changes.containsKey("active")) {
			if (!(changes.get("active") instanceof Boolean active)) {
				throw new InvalidGeofenceException("active must be true or false");
			}
			entity.setActive(active);
		}
		if (changes.containsKey("validFrom")) {
			entity.setValidFrom(instant(changes.get("validFrom"), "validFrom"));
		}
		if (changes.containsKey("validUntil")) {
			entity.setValidUntil(instant(changes.get("validUntil"), "validUntil"));
		}
		checkValidity(entity.getValidFrom(), entity.getValidUntil());
		GeofenceFeatureDto updated = GeofenceFeatureDto.from(repository.save(entity), clock.instant());
		publish();
		return updated;
	}

	/**
	 * @throws GeofenceNotFoundException  when the zone does not exist
	 * @throws PermanentGeofenceException when the zone is PERMANENT
	 */
	public void delete(long id) {
		loadChangeable(id);
		if (repository.deleteGeofence(id) == 0) {
			throw new GeofenceNotFoundException(id);
		}
		publish();
	}

	/** Sends the current collection to /topic/geofences. */
	public synchronized void publish() {
		GeofenceFeatureCollectionDto collection = findAll(clock.instant());
		lastEffectiveIds = collection.features().stream()
				.filter(feature -> feature.properties().effective())
				.map(GeofenceFeatureDto::id)
				.toList();
		messagingTemplate.convertAndSend(TOPIC, collection);
		eventPublisher.publishEvent(new GeofencesChangedEvent());
	}

	/** Sends the collection when the set of effective zones changed since it was last sent. */
	@Scheduled(fixedRateString = "${otonom.geofence.effectiveness-check-interval:10s}")
	public synchronized void publishIfEffectivenessChanged() {
		List<Long> effective = repository.findEffectiveIds(clock.instant());
		if (lastEffectiveIds == null) {
			// Clients load the list themselves when they connect; nothing changed for them yet.
			lastEffectiveIds = effective;
		} else if (!effective.equals(lastEffectiveIds)) {
			publish();
		}
	}

	private GeofenceEntity loadChangeable(long id) {
		GeofenceEntity entity = repository.findById(id).orElseThrow(() -> new GeofenceNotFoundException(id));
		if (entity.getKind() == GeofenceKind.PERMANENT) {
			throw new PermanentGeofenceException(id);
		}
		return entity;
	}

	private static void checkValidity(Instant validFrom, Instant validUntil) {
		if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
			throw new InvalidGeofenceException("validUntil (" + validUntil + ") must be after validFrom (" + validFrom + ")");
		}
	}

	/** null, or an ISO 8601 time with a zone or offset, e.g. 2026-11-08T12:00:00Z or 2026-11-08T15:00:00+03:00. */
	private static Instant instant(Object value, String field) {
		if (value == null) {
			return null;
		}
		if (value instanceof String text) {
			try {
				return OffsetDateTime.parse(text).toInstant();
			} catch (DateTimeParseException e) {
				// Reported below.
			}
		}
		throw new InvalidGeofenceException(field + " must be null or an ISO 8601 time with an offset, "
				+ "e.g. 2026-11-08T12:00:00Z, was " + value);
	}

	private void checkInDatabase(Polygon polygon) {
		List<Object[]> rows = repository.checkPolygon(polygon.toText());
		Object[] row = rows.get(0);
		if (!Boolean.TRUE.equals(row[0])) {
			throw new InvalidGeofenceException("polygon is not valid: " + row[1]);
		}
		double area = ((Number) row[2]).doubleValue();
		if (area > GeofenceValidator.MAX_AREA_M2) {
			throw new InvalidGeofenceException(String.format(Locale.ROOT,
					"polygon covers %.2f km², at most %.0f km² is allowed",
					area / 1_000_000, GeofenceValidator.MAX_AREA_M2 / 1_000_000));
		}
	}

}
