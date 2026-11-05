package com.otonomc3.core.alarm.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.springframework.stereotype.Component;

/**
 * GPS_DEGRADED (WARNING): fix type below gps-min-fix-type or fewer satellites than
 * gps-min-satellites. Each value is checked only when it is known, so a null value
 * never triggers the rule.
 */
@Component
public class GpsDegradedRule implements AlarmRule {

	private final AlarmProperties properties;

	public GpsDegradedRule(AlarmProperties properties) {
		this.properties = properties;
	}

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		if (state.linkStatus() != LinkStatus.ONLINE) {
			return Optional.empty();
		}
		TelemetryMessageDto.Gps gps = state.message().gps();
		if (gps == null) {
			return Optional.empty();
		}
		List<String> reasons = new ArrayList<>(2);
		if (gps.fixType() != null && gps.fixType() < properties.gpsMinFixType()) {
			reasons.add("fix tipi " + gps.fixType() + " (en az " + properties.gpsMinFixType() + ")");
		}
		if (gps.satellites() != null && gps.satellites() < properties.gpsMinSatellites()) {
			reasons.add(gps.satellites() + " uydu (en az " + properties.gpsMinSatellites() + ")");
		}
		if (reasons.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new AlarmCondition(AlarmType.GPS_DEGRADED, AlarmSeverity.WARNING,
				"GPS zayıf: " + String.join(", ", reasons)));
	}

}
