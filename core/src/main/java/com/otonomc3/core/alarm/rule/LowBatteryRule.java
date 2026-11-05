package com.otonomc3.core.alarm.rule;

import java.util.Optional;

import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.springframework.stereotype.Component;

/** LOW_BATTERY: below battery-warning-percent is a WARNING, below battery-critical-percent CRITICAL. */
@Component
public class LowBatteryRule implements AlarmRule {

	private final AlarmProperties properties;

	public LowBatteryRule(AlarmProperties properties) {
		this.properties = properties;
	}

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		// Old data must not raise or change alarms; the LINK rule covers that case.
		if (state.linkStatus() != LinkStatus.ONLINE) {
			return Optional.empty();
		}
		TelemetryMessageDto.Battery battery = state.message().battery();
		Integer percent = battery == null ? null : battery.percent();
		if (percent == null) {
			return Optional.empty();
		}
		if (percent < properties.batteryCriticalPercent()) {
			return Optional.of(new AlarmCondition(AlarmType.LOW_BATTERY, AlarmSeverity.CRITICAL,
					"Batarya kritik: %" + percent + " (eşik %" + properties.batteryCriticalPercent() + ")"));
		}
		if (percent < properties.batteryWarningPercent()) {
			return Optional.of(new AlarmCondition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING,
					"Batarya düşük: %" + percent + " (eşik %" + properties.batteryWarningPercent() + ")"));
		}
		return Optional.empty();
	}

}
