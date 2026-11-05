package com.otonomc3.core.alarm.rule;

import java.util.Optional;

import com.otonomc3.core.drone.service.DroneLiveState;

/**
 * Checks one kind of problem on a drone's live state. Implementations are Spring
 * beans; the alarm engine runs all of them on every drone.
 *
 * <p>Rules must not trigger on unknown (null) values.
 */
public interface AlarmRule {

	/** @return the condition when the problem is present, otherwise empty */
	Optional<AlarmCondition> evaluate(DroneLiveState state);

}
