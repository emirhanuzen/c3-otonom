package com.otonomc3.core.alarm.rule;

import java.util.Optional;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.service.GeofenceChecker;
import org.springframework.stereotype.Component;

/**
 * GEOFENCE_VIOLATION (CRITICAL): the drone is inside at least one active no-fly
 * zone. Reads the result of the GeofenceChecker run of the current evaluation.
 */
@Component
public class GeofenceViolationRule implements AlarmRule {

	private final GeofenceChecker checker;

	public GeofenceViolationRule(GeofenceChecker checker) {
		this.checker = checker;
	}

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		if (state.linkStatus() != LinkStatus.ONLINE) {
			return Optional.empty();
		}
		return checker.statusOf(state.droneId())
				.filter(status -> !status.insideZones().isEmpty())
				.map(status -> new AlarmCondition(AlarmType.GEOFENCE_VIOLATION, AlarmSeverity.CRITICAL,
						"Yasak bölge ihlali: " + String.join(", ", status.insideZones())));
	}

}
