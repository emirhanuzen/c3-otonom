package com.otonomc3.core.fence.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.otonomc3.core.config.FenceProperties;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import com.otonomc3.core.geofence.service.GeofenceKind;
import com.otonomc3.core.geofence.service.GeofenceSource;
import com.otonomc3.core.geofence.service.GeofenceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the planner's queries on the real PostGIS database, like GeofenceCheckerTest
 * (docker compose up postgis). The zones made here lie around 10° N 10° E, far from
 * every real zone, and are deleted afterwards. At this latitude 0.001° is about 110 m.
 */
@SpringBootTest
class FencePlannerTest {

	private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
	private static final double LAT = 10.0;
	private static final double LON = 10.0;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private GeofenceRepository repository;

	private final List<Long> created = new ArrayList<>();

	@AfterEach
	void deleteZones() {
		created.forEach(repository::deleteById);
	}

	@Test
	void simplifiedZoneCoversTheZoneWithinTheVertexLimit() {
		// A 64-vertex star, about 1.1 km across: far more vertices than the limit allows.
		long star = save("star", star(LAT, LON + 0.02, 64, 0.005, 0.003), true, null);

		FencePlan plan = planner(properties(24, 150)).plan(LAT, LON, T0, FenceAction.RTL);

		FenceZone zone = zone(plan, star);
		assertThat(zone.vertexCount()).isBetween(3, 24);
		assertThat(zone.marginM()).isGreaterThanOrEqualTo(50);
		assertThat(covers(zone, star)).isTrue();
	}

	@Test
	void simpleZoneKeepsTheFirstMarginAndCoversTheZone() {
		long square = save("square", square(LAT, LON + 0.02, 0.003), true, null);

		FenceZone zone = zone(planner(properties(24, 150)).plan(LAT, LON, T0, FenceAction.RTL), square);

		assertThat(zone.marginM()).isEqualTo(50);
		assertThat(zone.vertexCount()).isLessThanOrEqualTo(24);
		assertThat(covers(zone, square)).isTrue();
		// The ring goes to the autopilot open: its last vertex is not the first one again.
		assertThat(zone.vertices().getLast()).isNotEqualTo(zone.vertices().getFirst());
	}

	@Test
	void zoneThatCannotBeSimplifiedEnoughGetsItsBoundingRectangle() {
		long star = save("star", star(LAT, LON + 0.02, 64, 0.005, 0.003), true, null);

		// 4 vertices: only a rectangle fits.
		FenceZone zone = zone(planner(properties(4, 150)).plan(LAT, LON, T0, FenceAction.RTL), star);

		assertThat(zone.vertexCount()).isLessThanOrEqualTo(4);
		assertThat(covers(zone, star)).isTrue();
	}

	@Test
	void zonesAreNearestFirstAndTheFarthestAreLeftOutAboveTheTotalLimit() {
		long near = save("near", square(LAT, LON + 0.01, 0.002), true, null);
		long middle = save("middle", square(LAT, LON + 0.03, 0.002), true, null);
		long far = save("far", square(LAT, LON + 0.05, 0.002), true, null);

		FencePlan all = planner(properties(24, 150)).plan(LAT, LON, T0, FenceAction.RTL);
		assertThat(ids(all)).containsExactly(near, middle, far);

		int twoZones = zone(all, near).vertexCount() + zone(all, middle).vertexCount();
		FencePlan limited = planner(properties(24, twoZones)).plan(LAT, LON, T0, FenceAction.RTL);
		assertThat(ids(limited)).containsExactly(near, middle);
		assertThat(limited.vertexCount()).isLessThanOrEqualTo(twoZones);
		assertThat(limited.excludedZones()).containsExactly(name(far));
	}

	@Test
	void onlyEffectiveZonesWithinTheRadiusAreTaken() {
		long inRange = save("in-range", square(LAT, LON + 0.1, 0.002), true, null);       // ~11 km
		long outOfRange = save("out-of-range", square(LAT, LON + 0.3, 0.002), true, null); // ~33 km
		long inactive = save("inactive", square(LAT, LON + 0.02, 0.002), false, null);
		long expired = save("expired", square(LAT, LON + 0.04, 0.002), true, T0);

		FencePlan plan = planner(properties(24, 150)).plan(LAT, LON, T0, FenceAction.RTL);

		assertThat(ids(plan)).contains(inRange).doesNotContain(outOfRange, inactive, expired);
	}

	@Test
	void versionFollowsTheContent() {
		save("square", square(LAT, LON + 0.02, 0.003), true, null);
		FencePlanner planner = planner(properties(24, 150));

		FencePlan first = planner.plan(LAT, LON, T0, FenceAction.RTL);
		assertThat(planner.plan(LAT, LON, T0, FenceAction.RTL).version()).isEqualTo(first.version());
		assertThat(planner.plan(LAT, LON, T0, FenceAction.REPORT).version()).isNotEqualTo(first.version());

		long added = save("added", square(LAT, LON - 0.02, 0.003), true, null);
		FencePlan second = planner.plan(LAT, LON, T0, FenceAction.RTL);
		assertThat(ids(second)).contains(added);
		assertThat(second.version()).isNotEqualTo(first.version());
	}

	// --- Helpers -----------------------------------------------------------------------

	private FencePlanner planner(FenceProperties properties) {
		return new FencePlanner(jdbcTemplate, properties);
	}

	private static FenceProperties properties(int maxVerticesPerZone, int maxTotalVertices) {
		return new FenceProperties(true, FenceAction.RTL, 20_000, 50, maxVerticesPerZone, maxTotalVertices,
				Duration.ofSeconds(60), Duration.ofSeconds(2));
	}

	/** The zone made by this test; zones of other tests are ignored by id. */
	private static FenceZone zone(FencePlan plan, long geofenceId) {
		return plan.zones().stream().filter(zone -> zone.geofenceId() == geofenceId).findFirst().orElseThrow();
	}

	private List<Long> ids(FencePlan plan) {
		return plan.zones().stream().map(FenceZone::geofenceId).filter(created::contains).toList();
	}

	private String name(long id) {
		return repository.findById(id).orElseThrow().getName();
	}

	/** ST_Covers in WGS84 coordinates, the plane the autopilot checks its fences in. */
	private boolean covers(FenceZone zone, long geofenceId) {
		List<List<Double>> ring = new ArrayList<>(zone.vertices());
		ring.add(ring.getFirst());
		String wkt = ring.stream().map(v -> v.get(1) + " " + v.get(0))
				.collect(Collectors.joining(", ", "POLYGON((", "))"));
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"SELECT ST_Covers(ST_GeomFromText(?, 4326), CAST(area AS geometry)) FROM geofence WHERE id = ?",
				Boolean.class, wkt, geofenceId));
	}

	private long save(String label, GeoJsonPolygonDto polygon, boolean active, Instant validUntil) {
		GeofenceEntity entity = new GeofenceEntity();
		entity.setName("TEST-" + label + "-" + UUID.randomUUID());
		entity.setKind(GeofenceKind.TEMPORARY);
		entity.setSource(GeofenceSource.OPERATOR);
		entity.setValidUntil(validUntil);
		entity.setArea(GeofenceValidator.toPolygon(polygon));
		entity.setActive(active);
		entity.setCreatedAt(T0);
		long id = repository.saveAndFlush(entity).getId();
		created.add(id);
		return id;
	}

	private static GeoJsonPolygonDto square(double lat, double lon, double half) {
		return polygon(List.of(
				List.of(lon - half, lat - half), List.of(lon + half, lat - half), List.of(lon + half, lat + half),
				List.of(lon - half, lat + half), List.of(lon - half, lat - half)));
	}

	/** A star with {@code points} vertices alternating between two radii (degrees). */
	private static GeoJsonPolygonDto star(double lat, double lon, int points, double outer, double inner) {
		List<List<Double>> ring = new ArrayList<>();
		for (int i = 0; i < points; i++) {
			double angle = 2 * Math.PI * i / points;
			double radius = i % 2 == 0 ? outer : inner;
			ring.add(List.of(lon + radius * Math.cos(angle), lat + radius * Math.sin(angle)));
		}
		ring.add(ring.getFirst());
		return polygon(ring);
	}

	private static GeoJsonPolygonDto polygon(List<List<Double>> ring) {
		return new GeoJsonPolygonDto("Polygon", List.of(ring));
	}

}
