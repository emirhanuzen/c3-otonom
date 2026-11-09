package com.otonomc3.core.alarm.rule;

import java.util.Optional;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.springframework.stereotype.Component;

/**
 * FENCE_BREACH (CRITICAL): the autopilot reports a breach of its onboard fence
 * (status.fenceBreached = true), so its fence action (e.g. RTL) has taken over.
 * Unknown (null) never triggers.
 */
@Component
public class FenceBreachRule implements AlarmRule {

	public static final String MESSAGE = "Otopilot yasak bölge koruması devreye girdi";

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		if (state.linkStatus() != LinkStatus.ONLINE) {
			return Optional.empty();
		}
		TelemetryMessageDto.Status status = state.message().status();
		if (status == null || !Boolean.TRUE.equals(status.fenceBreached())) {
			return Optional.empty();
		}
		return Optional.of(new AlarmCondition(AlarmType.FENCE_BREACH, AlarmSeverity.CRITICAL, MESSAGE));
	}

}
