package com.otonomc3.core.drone.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.otonomc3.core.config.LiveProperties;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Keeps the newest telemetry of each drone in memory. Nothing is stored; the
 * state is rebuilt from the live queue after a restart.
 *
 * <p>Within one adapter session a message replaces the current one only when its
 * seq is higher. A message from another session (the adapter restarted) replaces
 * it only when its timestamp is newer.
 */
@Slf4j
@Service
public class DroneLiveStateService {

	private final Clock clock;
	private final Duration staleAfter;
	private final Duration lostAfter;

	private final Map<Integer, Entry> states = new ConcurrentHashMap<>();

	public DroneLiveStateService(Clock clock, LiveProperties properties) {
		this.clock = clock;
		this.staleAfter = properties.staleAfter();
		this.lostAfter = properties.lostAfter();
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
