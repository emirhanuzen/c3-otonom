package com.otonomc3.core.geofence.service;

/** What a no-fly zone is for. */
public enum GeofenceKind {

	/** Imported (OpenStreetMap: military areas, airports); cannot be changed or deleted through the API. */
	PERMANENT,

	/** Drawn by an operator for a limited purpose, often with an end time. */
	TEMPORARY,

	/** Drawn by an operator for a mission. */
	MISSION

}
