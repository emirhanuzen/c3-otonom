package com.otonomc3.core.alarm.rule;

import java.util.Optional;

import com.otonomc3.core.drone.service.DroneLiveState;
import org.springframework.stereotype.Component;

/** LINK: STALE is a WARNING, LOST is CRITICAL. */
@Component
public class LinkRule implements AlarmRule {

	@Override
	public Optional<AlarmCondition> evaluate(DroneLiveState state) {
		long seconds = state.age().toSeconds();
		return switch (state.linkStatus()) {
			case ONLINE -> Optional.empty();
			case STALE -> Optional.of(new AlarmCondition(AlarmType.LINK, AlarmSeverity.WARNING,
					"Bağlantı gecikmeli: son mesaj " + seconds + " sn önce"));
			case LOST -> Optional.of(new AlarmCondition(AlarmType.LINK, AlarmSeverity.CRITICAL,
					"Bağlantı kayıp: son mesaj " + seconds + " sn önce"));
		};
	}

}
