package com.otonomc3.core.alarm.service;

/** No alarm exists with the given id. */
public class AlarmNotFoundException extends RuntimeException {

	private final long alarmId;

	public AlarmNotFoundException(long alarmId) {
		super("Alarm " + alarmId + " does not exist");
		this.alarmId = alarmId;
	}

	public long getAlarmId() {
		return alarmId;
	}

}
