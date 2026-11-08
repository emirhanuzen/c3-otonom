package com.otonomc3.core.geofence.service;

/** A PERMANENT zone was to be changed or deleted through the API; only an import may change it. */
public class PermanentGeofenceException extends RuntimeException {

	private final long geofenceId;

	public PermanentGeofenceException(long geofenceId) {
		super("Geofence " + geofenceId + " is PERMANENT and cannot be changed or deleted through the API");
		this.geofenceId = geofenceId;
	}

	public long getGeofenceId() {
		return geofenceId;
	}

}
