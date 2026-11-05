package com.otonomc3.core.drone.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.otonomc3.core.config.LiveProperties;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the newest telemetry of each drone in memory. Nothing is stored; the
 * state is rebuilt from the live queue after a restart.
 *
 * <p>Within one adapter session a message replaces the current one only when its
 * seq is higher. A message from another session (the adapter restarted) replaces
 * it only when its timestamp is newer.
 *
 * <p>A message older than otonom.live.max-message-age on arrival is not applied:
 * after an outage the edge adapter flushes its buffer, and replaying those
 * messages would make the map jump through old positions and refresh lastSeenAt
 * with stale data. The telemetry path still stores them. Rejections and messages
 * from the future (clock skew) are counted and logged once every 10 seconds.
 */
@Slf4j
@Service
public class DroneLiveStateService {

	private final Clock clock;
	private final Duration staleAfter;
	private final Duration lostAfter;
	private final Duration maxMessageAge;
	private final Duration maxClockSkew;

	private final AtomicLong rejectedLate = new AtomicLong();
	/** Largest age among the rejected messages since the last summary, in ms. */
	private final AtomicLong maxRejectedAgeMs = new AtomicLong();
	private final AtomicLong futureMessages = new AtomicLong();
	/** Largest skew among the future messages since the last summary, in ms. */
	private final AtomicLong maxFutureSkewMs = new AtomicLong();

	private final Map<Integer, Entry> states = new ConcurrentHashMap<>();

	public DroneLiveStateService(Clock clock, LiveProperties properties) {
		this.clock = clock;
		this.staleAfter = properties.staleAfter();
		this.lostAfter = properties.lostAfter();
		this.maxMessageAge = properties.maxMessageAge();
		this.maxClockSkew = properties.maxClockSkew();
	}

	/**
	 * Applies a message to its drone's state.
	 *
	 * @return true when the message became the drone's current state
	 */
	public boolean update(TelemetryMessageDto message) {
		if (!isValid(message)) {
			return false;
		}
		Instant receivedAt = clock.instant();
		Duration age = Duration.between(message.timestamp(), receivedAt);
		if (age.compareTo(maxMessageAge) > 0) {
			rejectedLate.incrementAndGet();
			maxRejectedAgeMs.accumulateAndGet(age.toMillis(), Math::max);
			return false;
		}
		if (age.negated().compareTo(maxClockSkew) > 0) {
			// Still applied; only reported, since the drone's or the adapter's clock is ahead.
			futureMessages.incrementAndGet();
			maxFutureSkewMs.accumulateAndGet(age.negated().toMillis(), Math::max);
		}
		boolean[] accepted = { false };
		states.compute(message.droneId(), (droneId, current) -> {
			if (current != null && !isNewer(message, current.message())) {
				return current;
			}
			accepted[0] = true;
			return new Entry(message, receivedAt);
		});
		return accepted[0];
	}

	public Optional<DroneLiveState> find(int droneId) {
		Entry entry = states.get(droneId);
		return entry == null ? Optional.empty() : Optional.of(toState(entry, clock.instant()));
	}

	/** All known drones, ordered by droneId. */
	public List<DroneLiveState> findAll() {
		Instant now = clock.instant();
		return states.values().stream()
				.map(entry -> toState(entry, now))
				.sorted(Comparator.comparingInt(DroneLiveState::droneId))
				.toList();
	}

	/** Logs and resets the late and clock skew counters; silent when both are zero. */
	@Scheduled(fixedRate = 10_000, initialDelay = 10_000)
	void logRejectionSummary() {
		long late = rejectedLate.getAndSet(0);
		long maxAgeMs = maxRejectedAgeMs.getAndSet(0);
		long future = futureMessages.getAndSet(0);
		long maxSkewMs = maxFutureSkewMs.getAndSet(0);
		if (late > 0) {
			log.info("Live state (last 10s): rejected {} late message(s) older than {} ms, oldest {} ms",
					late, maxMessageAge.toMillis(), maxAgeMs);
		}
		if (future > 0) {
			log.warn("Live state (last 10s): {} message(s) with a timestamp more than {} ms in the future, "
					+ "up to {} ms; check the clocks of the drones and the edge adapter",
					future, maxClockSkew.toMillis(), maxSkewMs);
		}
	}

	/** Late messages rejected since the last summary. */
	long rejectedLateCount() {
		return rejectedLate.get();
	}

	/** Future-dated messages seen since the last summary. */
	long futureMessageCount() {
		return futureMessages.get();
	}

	LinkStatus linkStatus(Duration age) {
		if (age.compareTo(staleAfter) < 0) {
			return LinkStatus.ONLINE;
		}
		if (age.compareTo(lostAfter) < 0) {
			return LinkStatus.STALE;
		}
		return LinkStatus.LOST;
	}

	private DroneLiveState toState(Entry entry, Instant now) {
		Duration age = Duration.between(entry.lastSeenAt(), now);
		return new DroneLiveState(entry.message(), entry.lastSeenAt(), age, linkStatus(age));
	}

	private static boolean isNewer(TelemetryMessageDto candidate, TelemetryMessageDto current) {
		if (candidate.sessionId().equals(current.sessionId())) {
			return candidate.seq() > current.seq();
		}
		return candidate.timestamp().isAfter(current.timestamp());
	}

	private static boolean isValid(TelemetryMessageDto message) {
		if (message.droneId() == null || message.droneId() <= 0
				|| message.sessionId() == null
				|| message.timestamp() == null
				|| message.seq() == null) {
			log.warn("Skipping invalid live telemetry: droneId={}, sessionId={}, seq={}, timestamp={}",
					message.droneId(), message.sessionId(), message.seq(), message.timestamp());
			return false;
		}
		return true;
	}

	/** lastSeenAt is when {@code message} was received. */
	private record Entry(TelemetryMessageDto message, Instant lastSeenAt) {
	}

}
