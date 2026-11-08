package com.otonomc3.core.geofence.service;

/** No geofence exists with the given id. */
public class GeofenceNotFoundException extends RuntimeException {

	private final long geofenceId;

	public GeofenceNotFoundException(long geofenceId) {
		super("Geofence " + geofenceId + " does not exist");
		this.geofenceId = geofenceId;
	}

	public long getGeofenceId() {
		return geofenceId;
	}

}
