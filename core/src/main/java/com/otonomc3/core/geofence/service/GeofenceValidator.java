package com.otonomc3.core.geofence.service;

import java.util.List;

import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Structural checks of a GeoJSON polygon, done before PostGIS is asked: the type,
 * at least 4 positions per ring, closed rings and coordinates in range. Whether
 * the polygon is valid (ST_IsValid) and its area are checked by
 * {@link GeofenceService} in the database.
 */
public final class GeofenceValidator {

	public static final int MIN_RING_POSITIONS = 4;
	/** A zone may cover at most this much, in m² (50 km²). */
	public static final double MAX_AREA_M2 = 50_000_000;

	private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private GeofenceValidator() {
	}

	/**
	 * @return the polygon with SRID 4326, x = longitude, y = latitude
	 * @throws InvalidGeofenceException when a structural check fails
	 */
	public static Polygon toPolygon(GeoJsonPolygonDto geoJson) {
		if (geoJson == null) {
			throw new InvalidGeofenceException("polygon is required");
		}
		if (!GeoJsonPolygonDto.TYPE.equals(geoJson.type())) {
			throw new InvalidGeofenceException("polygon.type must be \"Polygon\", was " + quote(geoJson.type()));
		}
		List<List<List<Double>>> rings = geoJson.coordinates();
		if (rings == null || rings.isEmpty()) {
			throw new InvalidGeofenceException("polygon.coordinates must contain at least one ring");
		}
		LinearRing shell = ring(rings.get(0), 0);
		LinearRing[] holes = new LinearRing[rings.size() - 1];
		for (int i = 1; i < rings.size(); i++) {
			holes[i - 1] = ring(rings.get(i), i);
		}
		return FACTORY.createPolygon(shell, holes);
	}

	private static LinearRing ring(List<List<Double>> positions, int index) {
		String name = "ring " + index;
		if (positions == null || positions.size() < MIN_RING_POSITIONS) {
			throw new InvalidGeofenceException(name + " must have at least " + MIN_RING_POSITIONS
					+ " positions (a closed triangle), had " + (positions == null ? 0 : positions.size()));
		}
		Coordinate[] coordinates = new Coordinate[positions.size()];
		for (int i = 0; i < positions.size(); i++) {
			coordinates[i] = coordinate(positions.get(i), name + ", position " + i);
		}
		if (!coordinates[0].equals2D(coordinates[coordinates.length - 1])) {
			throw new InvalidGeofenceException(name + " is not closed: the first and last positions must be equal");
		}
		return FACTORY.createLinearRing(coordinates);
	}

	private static Coordinate coordinate(List<Double> position, String name) {
		if (position == null || position.size() < 2 || position.get(0) == null || position.get(1) == null) {
			throw new InvalidGeofenceException(name + " must be [longitude, latitude]");
		}
		double lon = position.get(0);
		double lat = position.get(1);
		if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
			throw new InvalidGeofenceException(name + ": longitude must be between -180 and 180, was " + lon);
		}
		if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
			throw new InvalidGeofenceException(name + ": latitude must be between -90 and 90, was " + lat);
		}
		return new Coordinate(lon, lat);
	}

	private static String quote(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}

}
