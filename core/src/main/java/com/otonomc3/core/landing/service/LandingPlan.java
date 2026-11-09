package com.otonomc3.core.landing.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A landing at the home.
 *
 * @param homeLat     the touchdown point (the home), rounded to 7 decimals
 * @param homeSource  where the home came from
 * @param approachLat the approach point, approachDistanceM before the home against the landing heading
 * @param approachAlt the approach point's altitude above home
 * @param headingDeg  the direction of the landing, degrees from north
 */
public record LandingPlan(double homeLat, double homeLon, HomeSource homeSource, double approachLat,
		double approachLon, double approachAlt, double headingDeg) {

	public enum HomeSource {
		/** The autopilot's HOME_POSITION from the telemetry. */
		AUTOPILOT,
		/** The first position at which the core saw the drone on the ground. */
		FIRST_GROUND_POSITION
	}

	/** The params of the LAND command: approach [lat, lon, alt], land [lat, lon]. */
	public Map<String, Object> params() {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("approach", List.of(approachLat, approachLon, approachAlt));
		params.put("land", List.of(homeLat, homeLon));
		return params;
	}

}
