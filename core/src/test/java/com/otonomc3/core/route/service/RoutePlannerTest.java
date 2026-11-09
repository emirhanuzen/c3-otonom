package com.otonomc3.core.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import com.otonomc3.core.config.RouteProperties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/**
 * The planner on zones laid out in metres around the start (38.60° N, 39.20° E): x east, y north.
 * The target is 6 km east of the start unless a test says otherwise.
 */
class RoutePlannerTest {

	private static final double START_LAT = 38.60;
	private static final double START_LON = 39.20;
	private static final double CLEARANCE_M = 150;
	private static final GeometryFactory GEOMETRY = new GeometryFactory();
	private static final LocalProjection PROJECTION = new LocalProjection(START_LAT, START_LON);

	private final RoutePlanner planner = new RoutePlanner(new RouteProperties(CLEARANCE_M, 10_000, 22));

	@Test
	void clearStraightLineIsADirectRoute() {
		List<RouteObstacle> zones = List.of(zone("Kuzey", 2500, 1000, 3500, 2000));    // 1 km north of the line

		RoutePlan route = plan(6000, 0, zones);

		assertThat(route.direct()).isTrue();
		assertThat(route.waypoints()).hasSize(1);
		assertThat(local(route.waypoints().getFirst()).distance(new Coordinate(6000, 0))).isLessThan(0.05);
		assertThat(route.distanceM()).isCloseTo(6000, within(1.0));
		assertThat(route.avoidedZones()).isEmpty();
	}

	@Test
	void lineTouchingOnlyTheClearanceIsNotDirect() {
		// The zone is 100 m north of the line: outside it, but inside its 150 m clearance.
		List<RouteObstacle> zones = List.of(zone("Yakın", 2500, 100, 3500, 600));

		RoutePlan route = plan(6000, 0, zones);

		assertThat(route.direct()).isFalse();
		assertClear(route, zones);
	}

	@Test
	void goesAroundOneZone() {
		List<RouteObstacle> zones = List.of(zone("Kışla", 2500, -500, 3500, 500));

		RoutePlan route = plan(6000, 0, zones);

		assertThat(route.direct()).isFalse();
		assertThat(route.avoidedZones()).containsExactly("Kışla");
		assertThat(route.waypoints().size()).isGreaterThanOrEqualTo(3);  // two corners and the target
		assertThat(local(route.waypoints().getLast()).distance(new Coordinate(6000, 0))).isLessThan(0.05);
		// Around a 1 km square with 150 m clearance: a little longer than straight, far shorter than a detour.
		assertThat(route.distanceM()).isBetween(6000.0, 6600.0);
		assertClear(route, zones);
	}

	@Test
	void goesAroundTwoZones() {
		List<RouteObstacle> zones = List.of(
				zone("Batı", 1500, -400, 2300, 900),
				zone("Doğu", 3800, -900, 4600, 400));

		RoutePlan route = plan(6000, 0, zones);

		assertThat(route.direct()).isFalse();
		assertThat(route.avoidedZones()).containsExactly("Batı", "Doğu");
		assertClear(route, zones);
	}

	@Test
	void overlappingZonesAreAvoidedAsOne() {
		List<RouteObstacle> zones = List.of(
				zone("A", 2500, -800, 3200, 200),
				zone("B", 3100, -200, 3800, 900));

		RoutePlan route = plan(6000, 0, zones);

		assertThat(route.avoidedZones()).containsExactlyInAnyOrder("A", "B");
		assertClear(route, zones);
	}

	@Test
	void targetInsideAZoneIsRefused() {
		List<RouteObstacle> zones = List.of(zone("Havaalanı", 5500, -500, 6500, 500));

		assertBlocked(() -> plan(6000, 0, zones), RouteBlockedException.Reason.TARGET_IN_ZONE, "Havaalanı");
	}

	@Test
	void targetWithinTheClearanceIsRefused() {
		// 100 m south of the zone.
		List<RouteObstacle> zones = List.of(zone("Lojman", 5500, 100, 6500, 1000));

		assertBlocked(() -> plan(6000, 0, zones), RouteBlockedException.Reason.TARGET_IN_ZONE, "Lojman");
	}

	@Test
	void startInsideAZoneIsRefused() {
		List<RouteObstacle> zones = List.of(zone("Orduevi", -300, -300, 300, 300));

		assertBlocked(() -> plan(6000, 0, zones), RouteBlockedException.Reason.START_IN_ZONE, "Orduevi");
	}

	@Test
	void targetEnclosedByZonesHasNoRoute() {
		// Four walls around the target, 1.6 km x 1.6 km; the gaps (100 m) close with the clearance.
		List<RouteObstacle> zones = List.of(
				zone("Kuzey duvar", 5200, 700, 6800, 800),
				zone("Güney duvar", 5200, -800, 6800, -700),
				zone("Batı duvar", 5100, -600, 5200, 600),
				zone("Doğu duvar", 6800, -600, 6900, 600));

		assertBlocked(() -> plan(6000, 0, zones), RouteBlockedException.Reason.NO_ROUTE);
	}

	@Test
	void concaveZoneIsLeftAndNotEntered() {
		// A U open to the east; the target lies east of it, so the route goes around the closed side.
		List<RouteObstacle> zones = List.of(zone("U", new double[][] {
				{ 2000, -1500 }, { 4000, -1500 }, { 4000, -1000 }, { 2600, -1000 }, { 2600, 1000 }, { 4000, 1000 },
				{ 4000, 1500 }, { 2000, 1500 }, { 2000, -1500 } }));

		RoutePlan route = plan(6000, 0, zones);

		assertThat(route.direct()).isFalse();
		assertClear(route, zones);
	}

	// --- Helpers -----------------------------------------------------------------------

	private RoutePlan plan(double targetX, double targetY, List<RouteObstacle> zones) {
		Coordinate target = new Coordinate(targetX, targetY);
		return planner.plan(START_LAT, START_LON, PROJECTION.lat(target), PROJECTION.lon(target), zones);
	}

	/**
	 * Checks the route itself, independent of the planner's own obstacles: no leg crosses a zone,
	 * and every leg keeps at least 85% of the clearance from each zone (the planner keeps 90%;
	 * the rest is the rounding to 7 decimals and the corner segments of the buffer).
	 */
	private static void assertClear(RoutePlan route, List<RouteObstacle> zones) {
		List<Coordinate> points = new ArrayList<>();
		points.add(new Coordinate(0, 0));
		route.waypoints().forEach(point -> points.add(local(point)));
		LineString path = GEOMETRY.createLineString(points.toArray(Coordinate[]::new));
		for (RouteObstacle zone : zones) {
			Polygon area = localPolygon(zone.area());
			assertThat(path.intersects(area)).as("route crosses %s", zone.name()).isFalse();
			assertThat(path.distance(area)).as("distance to %s", zone.name()).isGreaterThanOrEqualTo(0.85 * CLEARANCE_M);
		}
	}

	private static void assertBlocked(ThrowingCallable call, RouteBlockedException.Reason reason, String... zones) {
		assertThatThrownBy(call)
				.isInstanceOfSatisfying(RouteBlockedException.class, e -> {
					assertThat(e.getReason()).isEqualTo(reason);
					assertThat(e.getZones()).containsExactly(zones);
				})
				.hasMessage(reason.message());
	}

	private static Coordinate local(List<Double> latLon) {
		return PROJECTION.toLocal(latLon.get(0), latLon.get(1));
	}

	private static Polygon localPolygon(Polygon lonLat) {
		Coordinate[] ring = lonLat.getExteriorRing().getCoordinates();
		Coordinate[] local = new Coordinate[ring.length];
		for (int i = 0; i < ring.length; i++) {
			local[i] = PROJECTION.toLocal(ring[i]);
		}
		return GEOMETRY.createPolygon(local);
	}

	private static RouteObstacle zone(String name, double minX, double minY, double maxX, double maxY) {
		return zone(name, new double[][] { { minX, minY }, { maxX, minY }, { maxX, maxY }, { minX, maxY }, { minX, minY } });
	}

	/** A zone from local metres, stored like the real ones: x = longitude, y = latitude. */
	private static RouteObstacle zone(String name, double[][] ring) {
		Coordinate[] lonLat = new Coordinate[ring.length];
		for (int i = 0; i < ring.length; i++) {
			Coordinate local = new Coordinate(ring[i][0], ring[i][1]);
			lonLat[i] = new Coordinate(PROJECTION.lon(local), PROJECTION.lat(local));
		}
		return new RouteObstacle(name, GEOMETRY.createPolygon(lonLat));
	}

}
