package com.otonomc3.core.geofence.service;

/** Where a no-fly zone came from. */
public enum GeofenceSource {

	/** Imported from OpenStreetMap data (the seed file or POST /api/geofences/import). */
	OSM,

	/** Drawn on the map or sent to POST /api/geofences. */
	OPERATOR

}
