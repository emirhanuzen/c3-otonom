package com.otonomc3.core.geofence.service;

/** POST /api/geofences/import was called while otonom.sim.enabled is false. */
public class GeofenceImportDisabledException extends RuntimeException {

	public GeofenceImportDisabledException() {
		super("Importing geofences through the API is only allowed when otonom.sim.enabled=true (profile \"sim\")");
	}

}
