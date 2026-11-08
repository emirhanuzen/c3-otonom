package com.otonomc3.core.alarm.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.otonomc3.core.alarm.dto.AlarmDto;
import com.otonomc3.core.alarm.rule.AlarmCondition;
import com.otonomc3.core.alarm.rule.AlarmRule;
import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.alarm.rule.AlarmType;
import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.service.GeofenceChecker;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every alarm rule on every drone each otonom.alarm.evaluation-interval and
 * keeps at most one active alarm per (droneId, type):
 * <ul>
 * <li>a condition without an active alarm raises one;</li>
 * <li>a condition whose severity differs from its active alarm updates that alarm;</li>
 * <li>an active alarm whose condition has not been seen for otonom.alarm.clear-after
 * is cleared. The delay keeps a condition that comes and goes from raising a new
 * alarm each time.</li>
 * </ul>
 *
 * <p>While a drone's link is not ONLINE its data is old, so its alarms other than
 * LINK are frozen: none is raised, updated or cleared, and their last-seen time
 * keeps moving forward, including on the first evaluation after the link is back,
 * so the condition gets the full clear-after on fresh data before the alarm
 * clears. A low battery alarm therefore survives a link loss.
 *
 * <p>Before the rules, the {@link GeofenceChecker} checks all drones against the
 * no-fly zones in one query; the geofence rules read its result. When that query
 * fails, the geofence alarms are held the same way for that evaluation, while the
 * other rules run as usual.
 */
@Slf4j
@Component
public class AlarmEngine {

	/** Held, instead of cleared, in an evaluation in which the geofence check failed. */
	private static final Set<AlarmType> GEOFENCE_TYPES = Set.of(AlarmType.GEOFENCE_VIOLATION,
			AlarmType.GEOFENCE_PREDICTED);

	private final DroneLiveStateService liveStateService;
	private final GeofenceChecker geofenceChecker;
	private final List<AlarmRule> rules;
	private final AlarmService alarmService;
	private final Clock clock;
	private final Duration clearAfter;

	/** Only used by evaluate(), which the scheduler never runs concurrently, so a HashMap is enough. */
	private final Map<AlarmKey, ActiveAlarm> active = new HashMap<>();
	/** Drones whose link was not ONLINE in the previous evaluation. */
	private Set<Integer> previouslyFrozen = Set.of();

	public AlarmEngine(DroneLiveStateService liveStateService, GeofenceChecker geofenceChecker, List<AlarmRule> rules,
			AlarmService alarmService, Clock clock, AlarmProperties properties) {
		this.liveStateService = liveStateService;
		this.geofenceChecker = geofenceChecker;
		this.rules = List.copyOf(rules);
		this.alarmService = alarmService;
		this.clock = clock;
		this.clearAfter = properties.clearAfter();
	}

	/**
	 * The in-memory state starts empty, so alarms left active by a previous run
	 * would never be cleared. Runs before the first evaluate(), because scheduling
	 * starts only after the context is refreshed.
	 */
	@PostConstruct
	void clearLeftoverAlarms() {
		int cleared = alarmService.clearAllActive(clock.instant());
		if (cleared > 0) {
			log.info("Cleared {} alarm(s) left active by a previous run", cleared);
		}
	}

	@Scheduled(fixedRateString = "${otonom.alarm.evaluation-interval:1s}")
	public void evaluate() {
		Instant now = clock.instant();
		Map<AlarmKey, AlarmCondition> seen = new HashMap<>();
		Set<Integer> frozenDrones = new HashSet<>();
		List<DroneLiveState> states = liveStateService.findAll();
		Set<AlarmType> heldTypes = checkGeofences(states) ? Set.of() : GEOFENCE_TYPES;
		for (DroneLiveState state : states) {
			boolean frozen = state.linkStatus() != LinkStatus.ONLINE;
			if (frozen) {
				frozenDrones.add(state.droneId());
			}
			for (AlarmRule rule : rules) {
				evaluate(rule, state)
						// The rules already skip old data; this keeps the freeze even if one does not.
						.filter(condition -> !frozen || condition.type() == AlarmType.LINK)
						.ifPresent(condition -> seen.put(new AlarmKey(state.droneId(), condition.type()), condition));
			}
		}
		seen.forEach((key, condition) -> apply(key, condition, now));
		// Drones thawing in this evaluation are still treated as frozen once.
		Set<Integer> holdDrones = new HashSet<>(frozenDrones);
		holdDrones.addAll(previouslyFrozen);
		clearUnseen(seen.keySet(), holdDrones, heldTypes, now);
		previouslyFrozen = frozenDrones;
	}

	/** @return false when the check failed; the checker logs that itself */
	private boolean checkGeofences(List<DroneLiveState> states) {
		try {
			return geofenceChecker.check(states);
		} catch (RuntimeException e) {
			log.error("Geofence check failed", e);
			return false;
		}
	}

	private static boolean isHeld(AlarmKey key, Set<Integer> holdDrones, Set<AlarmType> heldTypes) {
		return (key.type() != AlarmType.LINK && holdDrones.contains(key.droneId())) || heldTypes.contains(key.type());
	}

	private Optional<AlarmCondition> evaluate(AlarmRule rule, DroneLiveState state) {
		try {
			return rule.evaluate(state);
		} catch (RuntimeException e) {
			log.error("Alarm rule {} failed on drone {}", rule.getClass().getSimpleName(), state.droneId(), e);
			return Optional.empty();
		}
	}

	private void apply(AlarmKey key, AlarmCondition condition, Instant now) {
		ActiveAlarm current = active.get(key);
		try {
			if (current == null) {
				AlarmDto raised = alarmService.raise(key.droneId(), condition, now);
				active.put(key, new ActiveAlarm(raised.id(), condition.severity(), now));
				return;
			}
			// Marked as seen first, so a failed update below cannot get the alarm cleared.
			active.put(key, new ActiveAlarm(current.alarmId(), current.severity(), now));
			if (current.severity() != condition.severity()) {
				alarmService.updateSeverity(current.alarmId(), condition, now);
				active.put(key, new ActiveAlarm(current.alarmId(), condition.severity(), now));
			}
		} catch (RuntimeException e) {
			// Retried on the next evaluation.
			log.error("Could not store {} alarm of drone {}", key.type(), key.droneId(), e);
		}
	}

	/**
	 * Alarms of holdDrones other than LINK, and alarms of heldTypes, are not cleared;
	 * their last-seen time moves to now instead.
	 */
	private void clearUnseen(Set<AlarmKey> seen, Set<Integer> holdDrones, Set<AlarmType> heldTypes, Instant now) {
		Iterator<Map.Entry<AlarmKey, ActiveAlarm>> iterator = active.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<AlarmKey, ActiveAlarm> entry = iterator.next();
			ActiveAlarm alarm = entry.getValue();
			if (isHeld(entry.getKey(), holdDrones, heldTypes) && !seen.contains(entry.getKey())) {
				entry.setValue(new ActiveAlarm(alarm.alarmId(), alarm.severity(), now));
				continue;
			}
			if (seen.contains(entry.getKey())
					|| Duration.between(alarm.lastSeenAt(), now).compareTo(clearAfter) < 0) {
				continue;
			}
			try {
				alarmService.clear(alarm.alarmId(), now);
				iterator.remove();
			} catch (RuntimeException e) {
				log.error("Could not clear alarm {}", alarm.alarmId(), e);
			}
		}
	}

	private record AlarmKey(int droneId, AlarmType type) {
	}

	/** lastSeenAt is the last evaluation in which the alarm's condition was present. */
	private record ActiveAlarm(long alarmId, AlarmSeverity severity, Instant lastSeenAt) {
	}

}
