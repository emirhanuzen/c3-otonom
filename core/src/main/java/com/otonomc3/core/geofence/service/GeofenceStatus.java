package com.otonomc3.core.geofence.service;

import java.util.List;

/**
 * Where one drone is relative to the active no-fly zones, as found by one GeofenceChecker run.
 *
 * @param insideZones    names of the zones the drone is in
 * @param predictedZones names of the zones its projected path (otonom.alarm.geofence-lookahead at the
 *                       current ground speed and heading) crosses; includes the insideZones it is
 *                       still in at the start of that path
 */
public record GeofenceStatus(List<String> insideZones, List<String> predictedZones) {

	public static final GeofenceStatus NONE = new GeofenceStatus(List.of(), List.of());

	public GeofenceStatus {
		insideZones = List.copyOf(insideZones);
		predictedZones = List.copyOf(predictedZones);
	}

}
