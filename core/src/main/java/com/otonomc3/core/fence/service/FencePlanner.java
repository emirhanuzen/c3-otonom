package com.otonomc3.core.fence.service;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.otonomc3.core.config.FenceProperties;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Computes the onboard fences of a drone with PostGIS.
 *
 * <p>The zones effective now within otonom.fence.radius-m of the drone are taken nearest
 * first. Each zone is buffered and simplified in its UTM zone (metres): ST_Buffer(area,
 * margin), then ST_SimplifyPreserveTopology with a tolerance below the margin, holes
 * dropped. The first margin (otonom.fence.margin-m, doubled up to {@link #MARGIN_STEPS}
 * times) whose result has at most otonom.fence.max-vertices-per-zone vertices and
 * ST_Covers the zone in WGS84 is kept. A zone for which no margin works gets the
 * oriented bounding rectangle of its first buffer (4 vertices). Zones are added until
 * the next one would pass otonom.fence.max-total-vertices; it and the farther ones are
 * left out.
 *
 * <p>A zone's simplified polygon does not depend on the drone, so it is cached until
 * {@link #invalidate} (called when the zones change).
 */
@Slf4j
@Component
public class FencePlanner {

	/** Margins tried: margin-m * 2^0 .. 2^(MARGIN_STEPS - 1), i.e. 50 m .. 25.6 km by default. */
	static final int MARGIN_STEPS = 10;
	/** Simplification tolerance as a share of the margin; below 1 keeps the simplified ring outside the zone. */
	static final double TOLERANCE_RATIO = 0.9;

	private static final String NEARBY_SQL = """
			-- Same effectiveness rule as GeofenceRepository.EFFECTIVE.
			SELECT g.id, g.name, ST_Distance(g.area, d.point) AS distance
			FROM geofence g
			CROSS JOIN (SELECT CAST(ST_SetSRID(ST_MakePoint(?, ?), 4326) AS geography) AS point,
			                   CAST(? AS timestamptz) AS at) AS d
			WHERE g.active
			  AND (g.valid_from IS NULL OR g.valid_from <= d.at)
			  AND (g.valid_until IS NULL OR g.valid_until > d.at)
			  AND ST_DWithin(g.area, d.point, ?)
			ORDER BY distance, g.id
			""";

	/** UTM zone of the zone's centroid: 326xx north, 327xx south. */
	private static final String PROJECTED = """
			zone AS (
			    SELECT g.id, CAST(g.area AS geometry) AS geom
			    FROM geofence g
			    WHERE g.id = ANY (CAST(? AS bigint[]))
			),
			projected AS (
			    SELECT z.id, z.geom,
			           ST_Transform(z.geom, CAST((CASE WHEN ST_Y(ST_Centroid(z.geom)) >= 0 THEN 32600 ELSE 32700 END)
			               + LEAST(60, GREATEST(1, floor((ST_X(ST_Centroid(z.geom)) + 180) / 6) + 1)) AS integer))
			               AS area_m
			    FROM zone z
			)
			""";

	private static final String SIMPLIFY_SQL = "WITH " + PROJECTED + """
			, candidate AS (
			    SELECT p.id, p.geom, s.step, m.margin,
			           ST_Transform(ST_MakePolygon(ST_ExteriorRing(ST_SimplifyPreserveTopology(
			               ST_Buffer(p.area_m, m.margin, 'quad_segs=4'), m.margin * ?))), 4326) AS simple
			    FROM projected p
			    CROSS JOIN generate_series(0, ? - 1) AS s(step)
			    CROSS JOIN LATERAL (SELECT ? * power(2, s.step) AS margin) AS m
			)
			SELECT DISTINCT ON (id) id, margin, ST_AsText(simple) AS wkt
			FROM candidate
			WHERE ST_NPoints(simple) - 1 <= ?
			  AND ST_Covers(simple, geom)
			ORDER BY id, step
			""";

	private static final String FALLBACK_SQL = "WITH " + PROJECTED + """
			SELECT id, ST_AsText(simple) AS wkt, ST_Covers(simple, geom) AS covers
			FROM (SELECT id, geom,
			             ST_Transform(ST_OrientedEnvelope(ST_Buffer(area_m, ?, 'quad_segs=4')), 4326) AS simple
			      FROM projected) AS f
			""";

	private final JdbcTemplate jdbcTemplate;
	private final FenceProperties properties;
	/** geofence id -> its simplified polygon. */
	private final Map<Long, Simplified> cache = new ConcurrentHashMap<>();

	public FencePlanner(JdbcTemplate jdbcTemplate, FenceProperties properties) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
	}

	/** Forgets the simplified polygons; the zones changed. */
	public void invalidate() {
		cache.clear();
	}

	/** The fences of a drone at (lat, lon), with the zones effective at {@code at}. */
	public FencePlan plan(double lat, double lon, Instant at, FenceAction action) {
		List<Nearby> nearby = new ArrayList<>();
		jdbcTemplate.query(connection -> {
			PreparedStatement statement = connection.prepareStatement(NEARBY_SQL);
			statement.setDouble(1, lon);
			statement.setDouble(2, lat);
			statement.setObject(3, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
			statement.setDouble(4, properties.radiusM());
			return statement;
		}, row -> {
			nearby.add(new Nearby(row.getLong("id"), row.getString("name"), row.getDouble("distance")));
		});
		simplifyMissing(nearby.stream().map(Nearby::id).filter(id -> !cache.containsKey(id)).toList());

		List<FenceZone> zones = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		int total = 0;
		for (Nearby zone : nearby) {
			Simplified simplified = cache.get(zone.id());
			if (simplified == null) {
				continue; // deleted meanwhile
			}
			if (!excluded.isEmpty() || total + simplified.vertices().size() > properties.maxTotalVertices()) {
				excluded.add(zone.name());
				continue;
			}
			total += simplified.vertices().size();
			zones.add(new FenceZone(zone.id(), zone.name(), zone.distanceM(), simplified.marginM(),
					simplified.vertices()));
		}
		return FencePlan.of(action, zones, excluded);
	}

	private void simplifyMissing(List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		jdbcTemplate.query(connection -> {
			PreparedStatement statement = connection.prepareStatement(SIMPLIFY_SQL);
			statement.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
			statement.setDouble(2, TOLERANCE_RATIO);
			statement.setInt(3, MARGIN_STEPS);
			statement.setDouble(4, properties.marginM());
			statement.setInt(5, properties.maxVerticesPerZone());
			return statement;
		}, row -> {
			long id = row.getLong("id");
			cache.put(id, new Simplified(row.getDouble("margin"), vertices(row.getString("wkt"))));
		});

		List<Long> missing = ids.stream().filter(id -> !cache.containsKey(id)).toList();
		if (missing.isEmpty()) {
			return;
		}
		jdbcTemplate.query(connection -> {
			PreparedStatement statement = connection.prepareStatement(FALLBACK_SQL);
			statement.setArray(1, connection.createArrayOf("bigint", missing.toArray()));
			statement.setDouble(2, properties.marginM());
			return statement;
		}, row -> {
			long id = row.getLong("id");
			if (!row.getBoolean("covers")) {
				log.warn("Onboard fence of zone {}: its bounding rectangle does not cover it; zone left out", id);
				return;
			}
			log.info("Onboard fence of zone {}: no margin up to {} m gave at most {} vertices, using its "
					+ "bounding rectangle", id, properties.marginM() * Math.pow(2, MARGIN_STEPS - 1),
					properties.maxVerticesPerZone());
			cache.put(id, new Simplified(properties.marginM(), vertices(row.getString("wkt"))));
		});
	}

	/** WKT polygon (x = lon, y = lat) -> its open exterior ring as [lat, lon], rounded to 7 decimals. */
	static List<List<Double>> vertices(String wkt) {
		Geometry geometry;
		try {
			geometry = new WKTReader().read(wkt);
		} catch (ParseException e) {
			throw new IllegalStateException("PostGIS returned an unreadable polygon: " + wkt, e);
		}
		if (!(geometry instanceof Polygon polygon)) {
			throw new IllegalStateException("PostGIS returned a " + geometry.getGeometryType() + ", not a polygon");
		}
		Coordinate[] ring = polygon.getExteriorRing().getCoordinates();
		List<List<Double>> vertices = new ArrayList<>(ring.length - 1);
		for (int i = 0; i < ring.length - 1; i++) {
			vertices.add(List.of(round7(ring[i].getY()), round7(ring[i].getX())));
		}
		return vertices;
	}

	private static double round7(double value) {
		return Math.round(value * 1e7) / 1e7;
	}

	private record Nearby(long id, String name, double distanceM) {
	}

	private record Simplified(double marginM, List<List<Double>> vertices) {
	}

}
