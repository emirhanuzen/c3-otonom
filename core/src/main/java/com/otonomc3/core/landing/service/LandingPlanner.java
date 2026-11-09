package com.otonomc3.core.landing.service;

import com.otonomc3.core.config.LandingProperties;
import org.springframework.stereotype.Component;

/**
 * The geometry of a landing: the approach point lies otonom.landing.approach-distance-m from the
 * home against otonom.landing.heading-deg (great circle on a sphere of the mean Earth radius), at
 * otonom.landing.approach-alt-m. Pure computation.
 */
@Component
public class LandingPlanner {

	/** Mean Earth radius (m), as the route planner's LocalProjection. */
	static final double EARTH_RADIUS_M = 6_371_008.8;

	private final LandingProperties properties;

	public LandingPlanner(LandingProperties properties) {
		this.properties = properties;
	}

	public LandingPlan plan(double homeLat, double homeLon, LandingPlan.HomeSource source) {
		double heading = normalize(properties.headingDeg());
		double[] approach = destination(homeLat, homeLon, normalize(heading + 180), properties.approachDistanceM());
		return new LandingPlan(round7(homeLat), round7(homeLon), source, round7(approach[0]), round7(approach[1]),
				properties.approachAltM(), heading);
	}

	/** The point {@code distanceM} from (lat, lon) along the initial bearing {@code bearingDeg}. */
	static double[] destination(double lat, double lon, double bearingDeg, double distanceM) {
		double phi1 = Math.toRadians(lat);
		double lambda1 = Math.toRadians(lon);
		double theta = Math.toRadians(bearingDeg);
		double delta = distanceM / EARTH_RADIUS_M;
		double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
		double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
				Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
		double lon2 = (Math.toDegrees(lambda2) + 540) % 360 - 180;
		return new double[] { Math.toDegrees(phi2), lon2 };
	}

	private static double normalize(double degrees) {
		return ((degrees % 360) + 360) % 360;
	}

	private static double round7(double value) {
		return Math.round(value * 1e7) / 1e7;
	}

}
