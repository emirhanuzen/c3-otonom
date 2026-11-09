package com.otonomc3.core.route.service;

import org.locationtech.jts.geom.Coordinate;

/**
 * Equirectangular projection centred on one point: x metres east, y metres north of it.
 * Over the few tens of kilometres of a route the distortion is far below the clearance.
 */
final class LocalProjection {

	/** Mean Earth radius (m). */
	static final double EARTH_RADIUS_M = 6_371_008.8;

	private final double lat0;
	private final double lon0;
	private final double metresPerDegLat;
	private final double metresPerDegLon;

	LocalProjection(double lat0, double lon0) {
		this.lat0 = lat0;
		this.lon0 = lon0;
		this.metresPerDegLat = Math.toRadians(EARTH_RADIUS_M);
		this.metresPerDegLon = metresPerDegLat * Math.cos(Math.toRadians(lat0));
	}

	Coordinate toLocal(double lat, double lon) {
		return new Coordinate((lon - lon0) * metresPerDegLon, (lat - lat0) * metresPerDegLat);
	}

	/** x = longitude, y = latitude, as in the stored zones. */
	Coordinate toLocal(Coordinate lonLat) {
		return toLocal(lonLat.getY(), lonLat.getX());
	}

	double lat(Coordinate local) {
		return lat0 + local.getY() / metresPerDegLat;
	}

	double lon(Coordinate local) {
		return lon0 + local.getX() / metresPerDegLon;
	}

}
