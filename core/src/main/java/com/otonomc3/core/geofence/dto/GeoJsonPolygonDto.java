package com.otonomc3.core.geofence.dto;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/**
 * A GeoJSON Polygon (RFC 7946): the first ring is the outer boundary, any
 * further rings are holes. Each position is [longitude, latitude].
 */
@Schema(description = "GeoJSON Polygon. Her halka kapalıdır (ilk ve son nokta aynı), en az 4 noktadır; "
		+ "nokta [boylam, enlem] biçimindedir.")
public record GeoJsonPolygonDto(
		@Schema(description = "Her zaman \"Polygon\"", example = "Polygon")
		String type,
		@Schema(description = "Halkalar; ilki dış sınır, diğerleri delik",
				example = "[[[39.28, 38.60], [39.29, 38.60], [39.29, 38.61], [39.28, 38.61], [39.28, 38.60]]]")
		List<List<List<Double>>> coordinates) {

	public static final String TYPE = "Polygon";

	public static GeoJsonPolygonDto from(Polygon polygon) {
		List<List<List<Double>>> rings = new ArrayList<>(1 + polygon.getNumInteriorRing());
		rings.add(ring(polygon.getExteriorRing()));
		for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
			rings.add(ring(polygon.getInteriorRingN(i)));
		}
		return new GeoJsonPolygonDto(TYPE, rings);
	}

	private static List<List<Double>> ring(LineString ring) {
		List<List<Double>> positions = new ArrayList<>(ring.getNumPoints());
		for (Coordinate c : ring.getCoordinates()) {
			positions.add(List.of(c.getX(), c.getY()));
		}
		return positions;
	}

}
