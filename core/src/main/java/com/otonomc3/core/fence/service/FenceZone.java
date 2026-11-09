package com.otonomc3.core.fence.service;

import java.util.List;

/**
 * One no-fly zone as it goes to the autopilot: buffered by marginM and simplified so
 * that it covers the zone with few vertices.
 *
 * @param distanceM distance from the drone to the zone itself (0 inside it)
 * @param vertices  the open ring (no closing vertex), each [lat, lon] rounded to 7 decimals
 *                  (the precision of MAVLink's degrees * 10^7)
 */
public record FenceZone(long geofenceId, String name, double distanceM, double marginM, List<List<Double>> vertices) {

	public FenceZone {
		vertices = List.copyOf(vertices);
	}

	public int vertexCount() {
		return vertices.size();
	}

}
