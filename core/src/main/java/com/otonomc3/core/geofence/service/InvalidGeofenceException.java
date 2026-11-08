package com.otonomc3.core.geofence.service;

/** The polygon of a new geofence is malformed, not valid or too large. */
public class InvalidGeofenceException extends RuntimeException {

	public InvalidGeofenceException(String message) {
		super(message);
	}

}
