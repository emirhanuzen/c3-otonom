package com.otonomc3.core.alarm.service;

/** The parameters of an alarm list query are out of range. */
public class InvalidAlarmQueryException extends RuntimeException {

	public InvalidAlarmQueryException(String message) {
		super(message);
	}

}
