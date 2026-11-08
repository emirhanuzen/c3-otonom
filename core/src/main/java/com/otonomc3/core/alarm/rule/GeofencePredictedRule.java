package com.otonomc3.core.alarm.rule;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.service.GeofenceChecker;
import org.springframework.stereotype.Component;

/**
 * GEOFENCE_PREDICTED (WARNING): the drone is in no active no-fly zone, but its
 * path for the next otonom.alarm.geofence-lookahead crosses one. Once the drone
 * is inside a zone, GEOFENCE_VIOLATION takes over and this rule stays silent.
 */
@Component
public class GeofencePredictedRule implements AlarmRule {

	private final GeofenceChecker checker;
	private final String lookahead;

	public GeofencePredictedRule(GeofenceChecker checker, AlarmProperties properties) {
		this.checker = checker;
		this.lookahead = format(properties.geofenceLookahead());
	}

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		if (state.linkStatus() != LinkStatus.ONLINE) {
			return Optional.empty();
		}
		return checker.statusOf(state.droneId())
				.filter(status -> status.insideZones().isEmpty() && !status.predictedZones().isEmpty())
				.map(status -> new AlarmCondition(AlarmType.GEOFENCE_PREDICTED, AlarmSeverity.WARNING,
						lookahead + " içinde " + zones(status.predictedZones()) + " girecek"));
	}

	private static String zones(List<String> names) {
		return String.join(", ", names) + (names.size() == 1 ? " bölgesine" : " bölgelerine");
	}

	/** "30 sn", or "1,5 sn" for a fractional lookahead. */
	private static String format(Duration duration) {
		long millis = duration.toMillis();
		return (millis % 1000 == 0 ? String.valueOf(millis / 1000) : String.valueOf(millis / 1000.0).replace('.', ','))
				+ " sn";
	}

}
