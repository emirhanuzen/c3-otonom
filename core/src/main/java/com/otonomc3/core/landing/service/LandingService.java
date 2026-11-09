package com.otonomc3.core.landing.service;

import java.util.List;
import java.util.Optional;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.route.service.LegCheck;
import com.otonomc3.core.route.service.RoutePlanner;
import com.otonomc3.core.route.service.RouteService;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Plans the LAND of a drone at its home and checks it against the no-fly zones.
 *
 * <p>The home is the autopilot's HOME_POSITION from the telemetry, or else the first position at
 * which the core saw the drone on the ground in its current adapter session. The legs from the
 * drone to the approach point (the approach leg) and from the approach point to the home (the
 * landing leg) are checked like routes ({@link RoutePlanner#checkLeg}: zones widened by
 * otonom.route.clearance-m); a touching leg refuses the landing.
 */
@Slf4j
@Service
public class LandingService {

	private final LandingPlanner planner;
	private final RoutePlanner routePlanner;
	private final RouteService routeService;
	private final DroneLiveStateService liveStateService;

	public LandingService(LandingPlanner planner, RoutePlanner routePlanner, RouteService routeService,
			DroneLiveStateService liveStateService) {
		this.planner = planner;
		this.routePlanner = routePlanner;
		this.routeService = routeService;
		this.liveStateService = liveStateService;
	}

	/** @throws LandingBlockedException when the home or the position is unknown or a leg crosses a zone */
	public LandingPlan plan(DroneLiveState state) {
		TelemetryMessageDto.Position position = state.message().position();
		if (position == null || !isLatLon(position.lat(), position.lon())) {
			throw new LandingBlockedException(LandingBlockedException.Reason.NO_POSITION, List.of());
		}
		LandingPlan plan = home(state)
				.orElseThrow(() -> new LandingBlockedException(LandingBlockedException.Reason.NO_HOME, List.of()));

		LegCheck approach = check(position.lat(), position.lon(), plan.approachLat(), plan.approachLon());
		if (approach.blocked()) {
			throw new LandingBlockedException(LandingBlockedException.Reason.APPROACH_BLOCKED, approach.crossedZones());
		}
		LegCheck landing = check(plan.approachLat(), plan.approachLon(), plan.homeLat(), plan.homeLon());
		if (landing.blocked()) {
			throw new LandingBlockedException(LandingBlockedException.Reason.LANDING_BLOCKED, landing.crossedZones());
		}
		log.info("Landing of drone {} planned at its home {}, {} ({}), approach {}, {} at {} m", state.droneId(),
				plan.homeLat(), plan.homeLon(), plan.homeSource(), plan.approachLat(), plan.approachLon(),
				plan.approachAlt());
		return plan;
	}

	/** The landing at the autopilot's home, or at the first position on the ground. */
	private Optional<LandingPlan> home(DroneLiveState state) {
		TelemetryMessageDto.Home home = state.message().home();
		// ArduPilot reports 0, 0 before it has a home.
		if (home != null && isLatLon(home.lat(), home.lon()) && !(home.lat() == 0 && home.lon() == 0)) {
			return Optional.of(planner.plan(home.lat(), home.lon(), LandingPlan.HomeSource.AUTOPILOT));
		}
		return liveStateService.firstGroundPosition(state.droneId())
				.map(ground -> planner.plan(ground[0], ground[1], LandingPlan.HomeSource.FIRST_GROUND_POSITION));
	}

	private LegCheck check(double fromLat, double fromLon, double toLat, double toLon) {
		return routePlanner.checkLeg(fromLat, fromLon, toLat, toLon,
				routeService.obstaclesAlong(fromLat, fromLon, toLat, toLon));
	}

	private static boolean isLatLon(Double lat, Double lon) {
		return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90
				&& Math.abs(lon) <= 180;
	}

}
