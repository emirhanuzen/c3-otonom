package com.otonomc3.core.landing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.config.LandingProperties;
import com.otonomc3.core.config.RouteProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.route.service.RouteObstacle;
import com.otonomc3.core.route.service.RoutePlanner;
import com.otonomc3.core.route.service.RouteService;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Landing at a home at 38.60° N, 39.20° E with the real route planner (150 m clearance). Points
 * are laid out in metres around the home (x east, y north); the drone flies 2 km east, 1 km south.
 */
class LandingServiceTest {

	private static final double HOME_LAT = 38.60;
	private static final double HOME_LON = 39.20;
	private static final double M_PER_DEG_LAT = Math.toRadians(LandingPlanner.EARTH_RADIUS_M);
	private static final double M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos(Math.toRadians(HOME_LAT));
	private static final GeometryFactory GEOMETRY = new GeometryFactory();

	private final RouteService routeService = mock(RouteService.class);
	private final DroneLiveStateService liveStateService = mock(DroneLiveStateService.class);
	private final List<RouteObstacle> zones = new ArrayList<>();

	@BeforeEach
	void setUp() {
		when(routeService.obstaclesAlong(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(zones);
		when(liveStateService.firstGroundPosition(anyInt())).thenReturn(Optional.empty());
	}

	private LandingService service(double headingDeg) {
		return new LandingService(new LandingPlanner(new LandingProperties(headingDeg, 1200, 60)),
				new RoutePlanner(new RouteProperties(150, 10_000, 22)), routeService, liveStateService);
	}

	// --- the landing mission ------------------------------------------------------------

	@Test
	void northwardLandingApproaches1200MSouthOfTheHomeAt60M() {
		LandingPlan plan = service(0).plan(drone(local(2000, -1000), home()));

		assertThat(plan.homeSource()).isEqualTo(LandingPlan.HomeSource.AUTOPILOT);
		assertThat(plan.homeLat()).isEqualTo(HOME_LAT);
		assertThat(plan.homeLon()).isEqualTo(HOME_LON);
		assertThat((HOME_LAT - plan.approachLat()) * M_PER_DEG_LAT).isCloseTo(1200, within(0.5));
		assertThat(plan.approachLon()).isCloseTo(HOME_LON, within(1e-7));
		assertThat(plan.approachAlt()).isEqualTo(60);
		// The order of the mission the adapter flies: first the approach, then the touchdown at home.
		assertThat(List.copyOf(plan.params().keySet())).containsExactly("approach", "land");
		assertThat(plan.params()).isEqualTo(Map.of(
				"approach", List.of(plan.approachLat(), plan.approachLon(), 60.0),
				"land", List.of(HOME_LAT, HOME_LON)));
	}

	@Test
	void eastwardLandingApproachesFromTheWest() {
		LandingPlan plan = service(90).plan(drone(local(-3000, 0), home()));

		assertThat((HOME_LON - plan.approachLon()) * M_PER_DEG_LON).isCloseTo(1200, within(1.0));
		assertThat(plan.approachLat()).isCloseTo(HOME_LAT, within(1e-5));
		assertThat(plan.headingDeg()).isEqualTo(90);
	}

	@Test
	void withoutTheAutopilotsHomeTheFirstPositionOnTheGroundIsUsed() {
		when(liveStateService.firstGroundPosition(1)).thenReturn(Optional.of(new double[] { 38.61, 39.21 }));

		LandingPlan plan = service(0).plan(drone(local(2000, -1000), null));

		assertThat(plan.homeSource()).isEqualTo(LandingPlan.HomeSource.FIRST_GROUND_POSITION);
		assertThat(plan.homeLat()).isEqualTo(38.61);
		assertThat(plan.homeLon()).isEqualTo(39.21);
	}

	@Test
	void autopilotHomeAtZeroZeroIsNotAHome() {
		assertThatThrownBy(() -> service(0).plan(drone(local(2000, -1000), new TelemetryMessageDto.Home(0.0, 0.0, 0.0))))
				.isInstanceOf(LandingBlockedException.class)
				.extracting(e -> ((LandingBlockedException) e).getReason())
				.isEqualTo(LandingBlockedException.Reason.NO_HOME);
	}

	@Test
	void unknownPositionCannotLand() {
		assertThatThrownBy(() -> service(0).plan(drone(null, home())))
				.isInstanceOf(LandingBlockedException.class)
				.hasMessage("İHA'nın konumu bilinmiyor, iniş planlanamaz");
	}

	// --- the legs against the zones -------------------------------------------------------

	@Test
	void approachLegThroughAZoneIsRefused() {
		// Between the drone (2000, -1000) and the approach point (0, -1200).
		zones.add(zone("Şehit Astsubay Kışlası", 800, -1300, 1200, -900));

		assertThatThrownBy(() -> service(0).plan(drone(local(2000, -1000), home())))
				.isInstanceOf(LandingBlockedException.class)
				.hasMessage("Yaklaşma bacağı yasak bölgeden geçiyor: Şehit Astsubay Kışlası")
				.satisfies(e -> {
					LandingBlockedException blocked = (LandingBlockedException) e;
					assertThat(blocked.getReason()).isEqualTo(LandingBlockedException.Reason.APPROACH_BLOCKED);
					assertThat(blocked.getZones()).containsExactly("Şehit Astsubay Kışlası");
				});
	}

	@Test
	void approachPointInsideAZoneIsRefused() {
		zones.add(zone("Mühimmat deposu", -100, -1300, 100, -1100));

		assertThatThrownBy(() -> service(0).plan(drone(local(2000, -1000), home())))
				.isInstanceOf(LandingBlockedException.class)
				.extracting(e -> ((LandingBlockedException) e).getReason())
				.isEqualTo(LandingBlockedException.Reason.APPROACH_BLOCKED);
	}

	@Test
	void landingLegThroughAZoneIsRefused() {
		// On the final approach, between (0, -1200) and the home.
		zones.add(zone("Orduevi", -200, -700, 200, -500));

		assertThatThrownBy(() -> service(0).plan(drone(local(2000, -1000), home())))
				.isInstanceOf(LandingBlockedException.class)
				.hasMessage("İniş bacağı yasak bölgeden geçiyor: Orduevi");
	}

	@Test
	void zonesBesideBothLegsAllowTheLanding() {
		zones.add(zone("Batı", -2000, -800, -1500, 0));

		assertThat(service(0).plan(drone(local(2000, -1000), home())).homeLat()).isEqualTo(HOME_LAT);
	}

	// --- helpers -------------------------------------------------------------------------

	private static TelemetryMessageDto.Home home() {
		return new TelemetryMessageDto.Home(HOME_LAT, HOME_LON, 1010.0);
	}

	private static double[] local(double x, double y) {
		return new double[] { HOME_LAT + y / M_PER_DEG_LAT, HOME_LON + x / M_PER_DEG_LON };
	}

	private static RouteObstacle zone(String name, double x1, double y1, double x2, double y2) {
		double[] a = local(x1, y1);
		double[] b = local(x2, y2);
		return new RouteObstacle(name, GEOMETRY.createPolygon(new Coordinate[] {
				new Coordinate(a[1], a[0]), new Coordinate(b[1], a[0]), new Coordinate(b[1], b[0]),
				new Coordinate(a[1], b[0]), new Coordinate(a[1], a[0]) }));
	}

	private static DroneLiveState drone(double[] latLon, TelemetryMessageDto.Home home) {
		TelemetryMessageDto.Position position = latLon == null ? null
				: new TelemetryMessageDto.Position(latLon[0], latLon[1], 1110.0, 100.0, 270.0);
		TelemetryMessageDto message = new TelemetryMessageDto(2, UUID.randomUUID(), 1, 1L, Instant.EPOCH, position,
				null, null, null, new TelemetryMessageDto.Status("AUTO", true), home);
		return new DroneLiveState(message, Instant.EPOCH, Duration.ZERO, LinkStatus.ONLINE);
	}

}
