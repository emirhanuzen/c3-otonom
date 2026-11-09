package com.otonomc3.core.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.otonomc3.core.config.RouteProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.mission.dto.MissionLegDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.route.service.RouteBlockedException;
import com.otonomc3.core.route.service.RouteObstacle;
import com.otonomc3.core.route.service.RoutePlanner;
import com.otonomc3.core.route.service.RouteService;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

/**
 * The checker with the real route planner (150 m clearance) on missions laid out in metres around
 * 38.60° N, 39.20° E (x east, y north). One zone, "Kışla", is the square x 2500..3500, y -500..500.
 */
class MissionCheckerTest {

	private static final double LAT0 = 38.60;
	private static final double LON0 = 39.20;
	private static final double M_PER_DEG_LAT = Math.toRadians(6_371_008.8);
	private static final double M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos(Math.toRadians(LAT0));
	private static final GeometryFactory GEOMETRY = new GeometryFactory();
	private static final int MAX_WAYPOINTS = 100;

	private final RoutePlanner planner = new RoutePlanner(new RouteProperties(150, 10_000, 22));
	private final RouteService routeService = mock(RouteService.class);
	private final MissionChecker checker = new MissionChecker(planner, routeService);

	@BeforeEach
	void setUp() {
		List<RouteObstacle> zones = List.of(zone("Kışla", 2500, -500, 3500, 500));
		when(routeService.obstaclesAlong(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(zones);
	}

	// --- validate ---------------------------------------------------------------------

	@Test
	void clearMissionIsOk() {
		MissionValidationDto validation = checker.validate(
				List.of(point(0, 2000, 100), point(6000, 2000, 100), point(6000, 4000, 100)), false, null);

		assertThat(validation.ok()).isTrue();
		assertThat(validation.legs()).extracting(MissionLegDto::from, MissionLegDto::to, MissionLegDto::blocked)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(0, 1, false),
						org.assertj.core.groups.Tuple.tuple(1, 2, false));
		assertThat(validation.waypointsInZone()).isEmpty();
	}

	@Test
	void legThroughAZoneIsBlockedWithTheZoneName() {
		MissionValidationDto validation = checker.validate(
				List.of(point(0, 0, 100), point(6000, 0, 100), point(6000, 3000, 100)), false, null);

		assertThat(validation.ok()).isFalse();
		assertThat(validation.legs()).hasSize(2);
		assertThat(validation.legs().get(0)).isEqualTo(new MissionLegDto(0, 1, true, List.of("Kışla")));
		assertThat(validation.legs().get(1)).isEqualTo(new MissionLegDto(1, 2, false, List.of()));
		assertThat(validation.waypointsInZone()).isEmpty();
	}

	@Test
	void legTouchingOnlyTheClearanceIsBlocked() {
		// 100 m north of the zone: outside it, but inside its 150 m clearance.
		MissionValidationDto validation = checker.validate(List.of(point(0, 600, 100), point(6000, 600, 100)), false, null);

		assertThat(validation.legs().getFirst().blocked()).isTrue();
	}

	@Test
	void loopAddsTheLegFromTheLastWaypointBackToTheFirst() {
		// The closing leg 2 -> 0 runs through the zone; the others are clear.
		MissionValidationDto validation = checker.validate(
				List.of(point(0, 0, 100), point(0, 3000, 100), point(6000, 0, 100)), true, null);

		assertThat(validation.legs()).hasSize(3);
		assertThat(validation.legs().get(2)).isEqualTo(new MissionLegDto(2, 0, true, List.of("Kışla")));
		assertThat(validation.legs().get(0).blocked()).isFalse();
		assertThat(validation.ok()).isFalse();
	}

	@Test
	void droneLegComesFirstAndStartsAtTheDronePosition() {
		MissionValidationDto validation = checker.validate(
				List.of(point(0, 0, 100), point(0, 3000, 100)), false, drone(local(5000, 0)));

		assertThat(validation.legs()).hasSize(2);
		assertThat(validation.legs().getFirst()).isEqualTo(new MissionLegDto(null, 0, true, List.of("Kışla")));
		assertThat(validation.legs().get(1).blocked()).isFalse();
	}

	@Test
	void droneWithoutPositionCannotBeChecked() {
		assertThatThrownBy(() -> checker.validate(List.of(point(0, 0, 100), point(0, 3000, 100)), false, drone(null)))
				.isInstanceOf(RouteBlockedException.class)
				.extracting(e -> ((RouteBlockedException) e).getReason())
				.isEqualTo(RouteBlockedException.Reason.NO_POSITION);
	}

	@Test
	void waypointsInsideAZoneOrItsClearanceAreListed() {
		MissionValidationDto validation = checker.validate(
				List.of(point(0, 0, 100), point(3000, 0, 100), point(3000, 620, 100), point(3000, 3000, 100)), false,
				null);

		assertThat(validation.ok()).isFalse();
		// 1 is inside the zone, 2 is 120 m north of it (inside the clearance), 3 is clear.
		assertThat(validation.waypointsInZone()).containsExactly(1, 2);
		// With the zones around each, for the operator's reason ("2. nokta yasak bölgede: Kışla").
		assertThat(validation.waypointZones()).containsExactly(
				new com.otonomc3.core.mission.dto.MissionWaypointZoneDto(1, List.of("Kışla")),
				new com.otonomc3.core.mission.dto.MissionWaypointZoneDto(2, List.of("Kışla")));
	}

	// --- autofix ----------------------------------------------------------------------

	@Test
	void autofixInsertsTheDetourWithTheHigherAltitudeOfTheLeg() {
		List<MissionWaypointDto> mission = List.of(point(0, 0, 100), point(6000, 0, 150), point(6000, 3000, 120));

		MissionChecker.Fix fix = checker.autofix(mission, false, MAX_WAYPOINTS);

		assertThat(fix.added()).isNotEmpty();
		assertThat(fix.added()).startsWith(1);
		// The original waypoints keep their order around the inserted detour.
		assertThat(fix.waypoints().getFirst()).isEqualTo(mission.get(0));
		assertThat(fix.waypoints().get(fix.added().size() + 1)).isEqualTo(mission.get(1));
		assertThat(fix.waypoints().getLast()).isEqualTo(mission.get(2));
		assertThat(fix.waypoints()).hasSize(mission.size() + fix.added().size());
		fix.added().forEach(index -> assertThat(fix.waypoints().get(index).alt()).isEqualTo(150.0));
		assertThat(fix.validation().ok()).isTrue();
		assertThat(fix.validation().legs()).noneMatch(MissionLegDto::blocked);
	}

	@Test
	void autofixFixesTheClosingLegOfALoopAfterTheLastWaypoint() {
		List<MissionWaypointDto> mission = List.of(point(0, 0, 100), point(0, 3000, 100), point(6000, 0, 200));

		MissionChecker.Fix fix = checker.autofix(mission, true, MAX_WAYPOINTS);

		assertThat(fix.waypoints().subList(0, 3)).isEqualTo(mission);
		assertThat(fix.added()).first().isEqualTo(3);
		fix.added().forEach(index -> assertThat(fix.waypoints().get(index).alt()).isEqualTo(200.0));
		assertThat(fix.validation().ok()).isTrue();
	}

	@Test
	void autofixOfAClearMissionChangesNothing() {
		List<MissionWaypointDto> mission = List.of(point(0, 2000, 100), point(6000, 2000, 100));

		MissionChecker.Fix fix = checker.autofix(mission, false, MAX_WAYPOINTS);

		assertThat(fix.added()).isEmpty();
		assertThat(fix.waypoints()).isEqualTo(mission);
	}

	@Test
	void autofixRefusesWaypointsInsideAZone() {
		List<MissionWaypointDto> mission = List.of(point(0, 0, 100), point(3000, 0, 100), point(6000, 0, 100));

		assertThatThrownBy(() -> checker.autofix(mission, false, MAX_WAYPOINTS))
				.isInstanceOf(MissionNotFixableException.class)
				.hasMessageContaining("Yasak bölge içindeki noktalar otomatik düzeltilemez: 2")
				.extracting(e -> ((MissionNotFixableException) e).getWaypointsInZone())
				.isEqualTo(List.of(1));
	}

	@Test
	void autofixRefusesToGoBeyondTheWaypointLimit() {
		List<MissionWaypointDto> mission = List.of(point(0, 0, 100), point(6000, 0, 100));

		assertThatThrownBy(() -> checker.autofix(mission, false, 2))
				.isInstanceOf(MissionNotFixableException.class)
				.hasMessageContaining("en fazla 2 nokta");
	}

	// --- helpers ----------------------------------------------------------------------

	private static MissionWaypointDto point(double x, double y, double alt) {
		double[] latLon = local(x, y);
		return new MissionWaypointDto(latLon[0], latLon[1], alt);
	}

	/** [lat, lon] of the point x m east and y m north of the origin. */
	private static double[] local(double x, double y) {
		return new double[] { LAT0 + y / M_PER_DEG_LAT, LON0 + x / M_PER_DEG_LON };
	}

	/** A rectangle in metres, stored like the zones: x = longitude, y = latitude. */
	private static RouteObstacle zone(String name, double x1, double y1, double x2, double y2) {
		double[] a = local(x1, y1);
		double[] b = local(x2, y2);
		Polygon polygon = GEOMETRY.createPolygon(new Coordinate[] {
				new Coordinate(a[1], a[0]), new Coordinate(b[1], a[0]), new Coordinate(b[1], b[0]),
				new Coordinate(a[1], b[0]), new Coordinate(a[1], a[0]) });
		return new RouteObstacle(name, polygon);
	}

	private static DroneLiveState drone(double[] latLon) {
		TelemetryMessageDto.Position position = latLon == null ? null
				: new TelemetryMessageDto.Position(latLon[0], latLon[1], 1100.0, 100.0, 90.0);
		TelemetryMessageDto message = new TelemetryMessageDto(2, UUID.randomUUID(), 1, 1L, Instant.EPOCH, position,
				null, null, null, new TelemetryMessageDto.Status("AUTO", true));
		return new DroneLiveState(message, Instant.EPOCH, Duration.ZERO, LinkStatus.ONLINE);
	}

}
