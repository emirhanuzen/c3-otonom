package com.otonomc3.core.command.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the params of a command before it is sent. The edge adapter checks them
 * again; the limits here may be stricter (GOTO alt 30..500 m) because they also
 * protect the aircraft from operator mistakes.
 */
public final class CommandValidator {

	public static final double GOTO_MIN_ALT = 30;
	public static final double GOTO_MAX_ALT = 500;
	public static final double TAKEOFF_MIN_ALT = 30;
	public static final double TAKEOFF_MAX_ALT = 300;
	public static final double TAKEOFF_DEFAULT_ALT = 60;
	public static final double LINK_DROP_MIN_SECONDS = 1;
	public static final double LINK_DROP_MAX_SECONDS = 60;
	/** A drone below this relative altitude (m) counts as on the ground for the types that require flight. */
	public static final double MIN_AIRBORNE_ALT_REL = 10;
	/** DISARM is refused at or above this relative altitude (m)... */
	public static final double DISARM_MAX_ALT_REL = 5;
	/** ...or at or above this ground speed (m/s). */
	public static final double DISARM_MAX_GROUND_SPEED = 3;
	/** FENCE_UPLOAD limits, as in the edge adapter (command.py). */
	public static final Set<String> FENCE_ACTIONS = Set.of("RTL", "REPORT");
	public static final int FENCE_MIN_VERTICES = 3;
	public static final int FENCE_MAX_TOTAL_VERTICES = 500;
	public static final int FENCE_VERSION_MAX_LENGTH = 64;
	/** ROUTE limit, as in the edge adapter (command.py). */
	public static final int ROUTE_MAX_WAYPOINTS = 100;

	private CommandValidator() {
	}

	/**
	 * Returns the params the type needs, normalized (numbers as Double); unknown
	 * keys are dropped. A null params map counts as empty.
	 *
	 * @throws InvalidCommandException when a param is missing or out of range
	 */
	public static Map<String, Object> validate(CommandType type, Map<String, Object> params) {
		Map<String, Object> source = params == null ? Map.of() : params;
		Map<String, Object> result = new LinkedHashMap<>();
		switch (type) {
			case GOTO -> {
				result.put("lat", number(type, source, "lat", -90, 90));
				result.put("lon", number(type, source, "lon", -180, 180));
				result.put("alt", number(type, source, "alt", GOTO_MIN_ALT, GOTO_MAX_ALT));
			}
			case TAKEOFF -> result.put("alt", source.get("alt") == null ? TAKEOFF_DEFAULT_ALT
					: number(type, source, "alt", TAKEOFF_MIN_ALT, TAKEOFF_MAX_ALT));
			case FAULT_GPS_LOSS -> {
				if (!(source.get("enabled") instanceof Boolean enabled)) {
					throw new InvalidCommandException(type + ": enabled (true or false) is required");
				}
				result.put("enabled", enabled);
			}
			case FENCE_UPLOAD -> fenceUpload(source, result);
			case ROUTE -> result.put("waypoints", routeWaypoints(source));
			case FAULT_LINK_DROP -> result.put("seconds",
					number(type, source, "seconds", LINK_DROP_MIN_SECONDS, LINK_DROP_MAX_SECONDS));
			default -> {
				// No params.
			}
		}
		return result;
	}

	/** version, action and polygons as lists of [lat, lon] Double pairs; rings are open (no closing vertex). */
	private static void fenceUpload(Map<String, Object> source, Map<String, Object> result) {
		CommandType type = CommandType.FENCE_UPLOAD;
		if (!(source.get("version") instanceof String version) || version.isBlank()
				|| version.length() > FENCE_VERSION_MAX_LENGTH) {
			throw new InvalidCommandException(type + ": version (a non-empty string of at most "
					+ FENCE_VERSION_MAX_LENGTH + " characters) is required");
		}
		if (!(source.get("action") instanceof String action) || !FENCE_ACTIONS.contains(action)) {
			throw new InvalidCommandException(type + ": action must be RTL or REPORT, was " + source.get("action"));
		}
		if (!(source.get("polygons") instanceof List<?> polygons)) {
			throw new InvalidCommandException(type + ": polygons (a list of polygons, each a list of [lat, lon]) is required");
		}
		List<List<List<Double>>> normalized = new ArrayList<>(polygons.size());
		int total = 0;
		for (int i = 0; i < polygons.size(); i++) {
			if (!(polygons.get(i) instanceof List<?> polygon)) {
				throw new InvalidCommandException(type + ": polygons[" + i + "] must be a list of [lat, lon]");
			}
			List<List<Double>> vertices = new ArrayList<>(polygon.size());
			for (int j = 0; j < polygon.size(); j++) {
				vertices.add(vertex(polygon.get(j), "polygons[" + i + "][" + j + "]"));
			}
			if (vertices.size() > 1 && vertices.getFirst().equals(vertices.getLast())) {
				vertices.removeLast();
			}
			if (vertices.size() < FENCE_MIN_VERTICES) {
				throw new InvalidCommandException(type + ": polygons[" + i + "] needs at least " + FENCE_MIN_VERTICES
						+ " distinct vertices");
			}
			total += vertices.size();
			normalized.add(vertices);
		}
		if (total > FENCE_MAX_TOTAL_VERTICES) {
			throw new InvalidCommandException(type + ": at most " + FENCE_MAX_TOTAL_VERTICES
					+ " vertices in total, was " + total);
		}
		result.put("version", version);
		result.put("action", action);
		result.put("polygons", normalized);
	}

	/** waypoints as [lat, lon, alt] Double triples; alt as for GOTO (30..500 m above home). */
	private static List<List<Double>> routeWaypoints(Map<String, Object> source) {
		CommandType type = CommandType.ROUTE;
		if (!(source.get("waypoints") instanceof List<?> waypoints) || waypoints.isEmpty()
				|| waypoints.size() > ROUTE_MAX_WAYPOINTS) {
			throw new InvalidCommandException(type + ": waypoints (1 to " + ROUTE_MAX_WAYPOINTS
					+ " points [lat, lon, alt]) is required");
		}
		List<List<Double>> result = new ArrayList<>(waypoints.size());
		for (int i = 0; i < waypoints.size(); i++) {
			Object value = waypoints.get(i);
			if (value instanceof List<?> point && point.size() == 3 && point.get(0) instanceof Number lat
					&& point.get(1) instanceof Number lon && point.get(2) instanceof Number alt) {
				double latValue = lat.doubleValue();
				double lonValue = lon.doubleValue();
				double altValue = alt.doubleValue();
				if (Double.isFinite(latValue) && Double.isFinite(lonValue) && Double.isFinite(altValue)
						&& Math.abs(latValue) <= 90 && Math.abs(lonValue) <= 180
						&& altValue >= GOTO_MIN_ALT && altValue <= GOTO_MAX_ALT) {
					result.add(List.of(latValue, lonValue, altValue));
					continue;
				}
			}
			throw new InvalidCommandException(type + ": waypoints[" + i + "] must be [lat, lon, alt] with alt between "
					+ format(GOTO_MIN_ALT) + " and " + format(GOTO_MAX_ALT) + ", was " + value);
		}
		return result;
	}

	private static List<Double> vertex(Object value, String name) {
		if (value instanceof List<?> pair && pair.size() == 2
				&& pair.get(0) instanceof Number lat && pair.get(1) instanceof Number lon) {
			double latValue = lat.doubleValue();
			double lonValue = lon.doubleValue();
			if (Double.isFinite(latValue) && Double.isFinite(lonValue) && latValue >= -90 && latValue <= 90
					&& lonValue >= -180 && lonValue <= 180) {
				return List.of(latValue, lonValue);
			}
		}
		throw new InvalidCommandException(CommandType.FENCE_UPLOAD + ": " + name + " must be [lat, lon], was " + value);
	}

	private static double number(CommandType type, Map<String, Object> params, String name, double min, double max) {
		if (!(params.get(name) instanceof Number number)) {
			throw new InvalidCommandException(type + ": " + name + " (a number) is required");
		}
		double value = number.doubleValue();
		if (!Double.isFinite(value) || value < min || value > max) {
			throw new InvalidCommandException(
					type + ": " + name + " must be between " + format(min) + " and " + format(max) + ", was " + number);
		}
		return value;
	}

	private static String format(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

}
