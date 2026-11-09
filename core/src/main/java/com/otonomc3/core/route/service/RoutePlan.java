package com.otonomc3.core.route.service;

import java.util.List;

/**
 * A route from the drone's position to the target.
 *
 * @param direct       true when the straight line is clear and is the route
 * @param waypoints    the points to fly through after the start, each [lat, lon] rounded to 7
 *                     decimals; the last one is the target
 * @param distanceM    length of the route from the start
 * @param avoidedZones names of the zones the straight line would have crossed (widened by the
 *                     clearance), nearest first; empty for a direct route
 */
public record RoutePlan(boolean direct, List<List<Double>> waypoints, double distanceM, List<String> avoidedZones) {

	public RoutePlan {
		waypoints = List.copyOf(waypoints);
		avoidedZones = List.copyOf(avoidedZones);
	}

}
