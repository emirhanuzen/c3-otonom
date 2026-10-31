package com.otonomc3.core.telemetry.service;

/** The parameters of a telemetry history query are out of range. */
public class InvalidTelemetryQueryException extends RuntimeException {

	public InvalidTelemetryQueryException(String message) {
		super(message);
	}

}
