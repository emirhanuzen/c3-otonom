package com.otonomc3.core.alarm.rule;

/** What an alarm is about. A drone has at most one active alarm of each type. */
public enum AlarmType {

	/** The drone's link is STALE or LOST. */
	LINK,

	/** Battery percentage is below the warning or critical threshold. */
	LOW_BATTERY,

	/** GPS fix type or satellite count is too low. */
	GPS_DEGRADED,

	/** An armed drone in an autonomous mode flies below the minimum relative altitude. */
	LOW_ALTITUDE

}
