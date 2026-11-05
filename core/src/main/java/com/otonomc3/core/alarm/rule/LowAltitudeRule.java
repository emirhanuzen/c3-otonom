package com.otonomc3.core.alarm.rule;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.springframework.stereotype.Component;

/**
 * LOW_ALTITUDE (WARNING): the drone is airborne, armed, in one of
 * low-altitude-modes and below low-altitude-meters relative altitude. Armed
 * state, mode and altitude must all be known.
 *
 * <p>A drone on the ground or taking off is armed, in AUTO and near 0 m, which
 * would otherwise raise the alarm on every take-off. So the rule keeps an
 * airborne flag per drone: it is set once the armed drone reaches
 * low-altitude-meters + airborne-margin and removed when the drone is disarmed.
 * The rule is only checked while the flag is set. The flag is left alone while
 * the link is not ONLINE, so old data neither sets nor clears it.
 */
@Component
public class LowAltitudeRule implements AlarmRule {

	private final AlarmProperties properties;
	private final double airborneAltitude;

	/** droneId -> true once airborne; absent until then and after disarming. */
	private final Map<Integer, Boolean> airborne = new ConcurrentHashMap<>();

	public LowAltitudeRule(AlarmProperties properties) {
		this.properties = properties;
		this.airborneAltitude = properties.lowAltitudeMeters() + properties.airborneMargin();
	}

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		if (state.linkStatus() != LinkStatus.ONLINE) {
			return Optional.empty();
		}
		TelemetryMessageDto message = state.message();
		TelemetryMessageDto.Status status = message.status();
		TelemetryMessageDto.Position position = message.position();
		Boolean armed = status == null ? null : status.armed();
		Double altRel = position == null ? null : position.altRel();

		updateAirborne(state.droneId(), armed, altRel);

		if (!Boolean.TRUE.equals(airborne.get(state.droneId()))
				|| !Boolean.TRUE.equals(armed) || status.mode() == null || altRel == null) {
			return Optional.empty();
		}
		String mode = status.mode().trim().toUpperCase(Locale.ROOT);
		if (!properties.lowAltitudeModes().contains(mode) || altRel >= properties.lowAltitudeMeters()) {
			return Optional.empty();
		}
		return Optional.of(new AlarmCondition(AlarmType.LOW_ALTITUDE, AlarmSeverity.WARNING,
				String.format(Locale.ROOT, "Düşük irtifa: %.1f m, %s modunda (en az %.0f m)",
						altRel, mode, properties.lowAltitudeMeters())));
	}

	/** Unknown armed state or altitude leaves the flag as it is. */
	private void updateAirborne(int droneId, Boolean armed, Double altRel) {
		if (Boolean.FALSE.equals(armed)) {
			airborne.remove(droneId);
		} else if (Boolean.TRUE.equals(armed) && altRel != null && altRel >= airborneAltitude) {
			airborne.put(droneId, Boolean.TRUE);
		}
	}

}
