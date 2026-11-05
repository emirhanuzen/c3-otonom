package com.otonomc3.core.alarm.dto;

/** What happened to the alarm carried by an {@link AlarmEventDto}. */
public enum AlarmEventType {

	/** A new alarm became active. */
	RAISED,

	/** The severity of an active alarm changed. */
	UPDATED,

	/** The alarm's condition went away; the alarm is no longer active. */
	CLEARED,

	/** An operator acknowledged the alarm. */
	ACKNOWLEDGED

}
