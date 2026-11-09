package com.otonomc3.core.mission.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.mission.dto.MissionLegDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.dto.MissionWaypointZoneDto;
import com.otonomc3.core.route.service.LegCheck;
import com.otonomc3.core.route.service.RouteBlockedException;
import com.otonomc3.core.route.service.RoutePlan;
import com.otonomc3.core.route.service.RoutePlanner;
import com.otonomc3.core.route.service.RouteService;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.springframework.stereotype.Service;

/**
 * Checks missions against the effective no-fly zones with the route planner's obstacles: each
 * zone within otonom.route.corridor-m of a leg, widened by otonom.route.clearance-m
 * ({@link RoutePlanner#checkLeg}). Autofix adds the planner's detours ({@link RoutePlanner#plan})
 * to the blocked legs.
 */
@Service
public class MissionChecker {

	private final RoutePlanner planner;
	private final RouteService routeService;

	public MissionChecker(RoutePlanner planner, RouteService routeService) {
		this.planner = planner;
		this.routeService = routeService;
	}

	/**
	 * The legs, in this order: from the drone's position to the first waypoint (only when a drone
	 * is given), between consecutive waypoints, and for a loop from the last waypoint back to the first.
	 *
	 * @param drone null: the leg from the drone is left out
	 * @throws RouteBlockedException NO_POSITION when the drone's position is unknown
	 */
	public MissionValidationDto validate(List<MissionWaypointDto> waypoints, boolean loop, DroneLiveState drone) {
		List<MissionLegDto> legs = new ArrayList<>();
		/** waypoint index -> the zones around it, in the order the legs found them. */
		TreeMap<Integer, LinkedHashSet<String>> inZone = new TreeMap<>();
		MissionWaypointDto first = waypoints.getFirst();
		if (drone != null) {
			double[] position = position(drone);
			LegCheck check = check(position[0], position[1], first);
			addZones(inZone, 0, check.aroundTarget());
			legs.add(new MissionLegDto(null, 0, check.blocked(), check.crossedZones()));
		}
		for (int[] leg : missionLegs(waypoints.size(), loop)) {
			MissionWaypointDto from = waypoints.get(leg[0]);
			LegCheck check = check(from.lat(), from.lon(), waypoints.get(leg[1]));
			addZones(inZone, leg[0], check.aroundStart());
			addZones(inZone, leg[1], check.aroundTarget());
			legs.add(new MissionLegDto(leg[0], leg[1], check.blocked(), check.crossedZones()));
		}
		boolean ok = inZone.isEmpty() && legs.stream().noneMatch(MissionLegDto::blocked);
		List<MissionWaypointZoneDto> zones = inZone.entrySet().stream()
				.map(entry -> new MissionWaypointZoneDto(entry.getKey(), List.copyOf(entry.getValue())))
				.toList();
		return new MissionValidationDto(ok, legs, List.copyOf(inZone.keySet()), zones);
	}

	private static void addZones(Map<Integer, LinkedHashSet<String>> inZone, int index, List<String> zones) {
		if (!zones.isEmpty()) {
			inZone.computeIfAbsent(index, key -> new LinkedHashSet<>()).addAll(zones);
		}
	}

	/**
	 * Inserts the route planner's detour into every blocked leg; the new waypoints get the higher
	 * altitude of the leg's two ends. Waypoints inside a zone are never moved.
	 *
	 * @param maxWaypoints the mission's waypoint limit, which the fixed mission must keep
	 * @throws MissionNotFixableException when a waypoint is inside a zone, a leg has no safe detour,
	 *                                    or the fixed mission would have more than maxWaypoints
	 */
	public Fix autofix(List<MissionWaypointDto> waypoints, boolean loop, int maxWaypoints) {
		MissionValidationDto validation = validate(waypoints, loop, null);
		if (!validation.waypointsInZone().isEmpty()) {
			throw new MissionNotFixableException("Yasak bölge içindeki noktalar otomatik düzeltilemez: "
					+ numbers(validation.waypointsInZone()) + ". Bu noktaları taşıyın veya silin.",
					validation.waypointsInZone());
		}
		Map<Integer, MissionLegDto> blockedFrom = new TreeMap<>();
		validation.legs().stream().filter(MissionLegDto::blocked).forEach(leg -> blockedFrom.put(leg.from(), leg));

		List<MissionWaypointDto> fixed = new ArrayList<>();
		List<Integer> added = new ArrayList<>();
		for (int i = 0; i < waypoints.size(); i++) {
			MissionWaypointDto from = waypoints.get(i);
			fixed.add(from);
			MissionLegDto leg = blockedFrom.get(i);
			if (leg == null) {
				continue;
			}
			MissionWaypointDto to = waypoints.get(leg.to());
			RoutePlan route;
			try {
				route = planner.plan(from.lat(), from.lon(), to.lat(), to.lon(),
						routeService.obstaclesAlong(from.lat(), from.lon(), to.lat(), to.lon()));
			} catch (RouteBlockedException e) {
				throw new MissionNotFixableException("Bacak " + (i + 1) + " → " + (leg.to() + 1) + ": " + e.getMessage(),
						List.of());
			}
			double alt = Math.max(from.alt(), to.alt());
			// The last point of the route is the leg's end, which is already in the mission.
			for (List<Double> point : route.waypoints().subList(0, route.waypoints().size() - 1)) {
				added.add(fixed.size());
				fixed.add(new MissionWaypointDto(point.get(0), point.get(1), alt));
			}
		}
		if (fixed.size() > maxWaypoints) {
			throw new MissionNotFixableException("Düzeltilmiş görev " + fixed.size() + " nokta olurdu; en fazla "
					+ maxWaypoints + " nokta olabilir", List.of());
		}
		return new Fix(fixed, added, validate(fixed, loop, null));
	}

	/** [from, to] index pairs of the legs between the waypoints, the loop's closing leg last. */
	private static List<int[]> missionLegs(int count, boolean loop) {
		List<int[]> legs = new ArrayList<>();
		for (int i = 0; i + 1 < count; i++) {
			legs.add(new int[] { i, i + 1 });
		}
		if (loop && count >= 2) {
			legs.add(new int[] { count - 1, 0 });
		}
		return legs;
	}

	private LegCheck check(double fromLat, double fromLon, MissionWaypointDto to) {
		return planner.checkLeg(fromLat, fromLon, to.lat(), to.lon(),
				routeService.obstaclesAlong(fromLat, fromLon, to.lat(), to.lon()));
	}

	private static double[] position(DroneLiveState drone) {
		TelemetryMessageDto.Position position = drone.message().position();
		if (position == null || !isCoordinate(position.lat(), 90) || !isCoordinate(position.lon(), 180)) {
			throw new RouteBlockedException(RouteBlockedException.Reason.NO_POSITION, List.of());
		}
		return new double[] { position.lat(), position.lon() };
	}

	private static boolean isCoordinate(Double value, double limit) {
		return value != null && Double.isFinite(value) && Math.abs(value) <= limit;
	}

	/** "2, 5" for the indices 1 and 4: the operator counts from 1. */
	static String numbers(List<Integer> indices) {
		return indices.stream().map(index -> String.valueOf(index + 1)).collect(Collectors.joining(", "));
	}

	/**
	 * @param waypoints  the fixed mission's waypoints
	 * @param added      indices in {@code waypoints} of the inserted detour points
	 * @param validation the fixed mission's validation
	 */
	public record Fix(List<MissionWaypointDto> waypoints, List<Integer> added, MissionValidationDto validation) {
	}

}
