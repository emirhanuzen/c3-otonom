package com.otonomc3.core.route.service;

import java.time.Clock;
import java.util.List;

import com.otonomc3.core.config.RouteProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import com.otonomc3.core.route.dto.RoutePreviewDto;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.springframework.stereotype.Service;

/**
 * Plans GOTO routes around the effective no-fly zones within otonom.route.corridor-m of the
 * straight segment from the drone's latest position to the target, so the zones along the
 * way count however far the target is. Used by the route preview and, right before a
 * GOTO is sent, by CommandService.
 */
@Service
public class RouteService {

	/** Below this ground speed (m/s) the ETA uses otonom.route.default-speed-ms. */
	public static final double MIN_ETA_SPEED_MS = 5;

	private final RoutePlanner planner;
	private final GeofenceRepository geofenceRepository;
	private final DroneLiveStateService liveStateService;
	private final Clock clock;
	private final RouteProperties properties;

	public RouteService(RoutePlanner planner, GeofenceRepository geofenceRepository,
			DroneLiveStateService liveStateService, Clock clock, RouteProperties properties) {
		this.planner = planner;
		this.geofenceRepository = geofenceRepository;
		this.liveStateService = liveStateService;
		this.clock = clock;
		this.properties = properties;
	}

	/**
	 * @throws DroneNotFoundException when no live state is known for the drone
	 * @throws RouteBlockedException  when no safe route exists or the drone's position is unknown
	 */
	public RoutePreviewDto preview(int droneId, double lat, double lon) {
		DroneLiveState state = liveStateService.find(droneId).orElseThrow(() -> new DroneNotFoundException(droneId));
		RoutePlan plan = plan(state, lat, lon);
		return new RoutePreviewDto(plan.direct(), plan.waypoints(), Math.round(plan.distanceM()),
				Math.round(plan.distanceM() / etaSpeed(state.message())), plan.avoidedZones());
	}

	/** @throws RouteBlockedException when no safe route exists or the drone's position is unknown */
	public RoutePlan plan(DroneLiveState state, double lat, double lon) {
		TelemetryMessageDto.Position position = state.message().position();
		if (position == null || !isCoordinate(position.lat(), 90) || !isCoordinate(position.lon(), 180)) {
			throw new RouteBlockedException(RouteBlockedException.Reason.NO_POSITION, List.of());
		}
		return planner.plan(position.lat(), position.lon(), lat, lon,
				obstaclesAlong(position.lat(), position.lon(), lat, lon));
	}

	/**
	 * The zones effective now within otonom.route.corridor-m of the straight segment between the
	 * two points: the obstacles a route between them has to consider. Also used by mission validation.
	 */
	public List<RouteObstacle> obstaclesAlong(double startLat, double startLon, double targetLat, double targetLon) {
		return geofenceRepository
				.findEffectiveAlong(startLat, startLon, targetLat, targetLon, properties.corridorM(), clock.instant())
				.stream()
				.map(zone -> new RouteObstacle(zone.getName(), zone.getArea()))
				.toList();
	}

	private double etaSpeed(TelemetryMessageDto message) {
		Double speed = message.velocity() == null ? null : message.velocity().groundSpeed();
		return speed != null && Double.isFinite(speed) && speed >= MIN_ETA_SPEED_MS ? speed : properties.defaultSpeedMs();
	}

	private static boolean isCoordinate(Double value, double limit) {
		return value != null && Double.isFinite(value) && Math.abs(value) <= limit;
	}

}
