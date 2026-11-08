package com.otonomc3.core.command.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks the params of a command before it is sent. The edge adapter checks them
 * again; the limits here may be stricter (GOTO alt 30..500 m) because they also
 * protect the aircraft from operator mistakes.
 */
public final class CommandValidator {

	public static final double GOTO_MIN_ALT = 30;
	public static final double GOTO_MAX_ALT = 500;
	public static final double LINK_DROP_MIN_SECONDS = 1;
	public static final double LINK_DROP_MAX_SECONDS = 60;
	/** A drone below this relative altitude (m) counts as on the ground for the types that require flight. */
	public static final double MIN_AIRBORNE_ALT_REL = 10;

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
			case FAULT_GPS_LOSS -> {
				if (!(source.get("enabled") instanceof Boolean enabled)) {
					throw new InvalidCommandException(type + ": enabled (true or false) is required");
				}
				result.put("enabled", enabled);
			}
			case FAULT_LINK_DROP -> result.put("seconds",
					number(type, source, "seconds", LINK_DROP_MIN_SECONDS, LINK_DROP_MAX_SECONDS));
			default -> {
				// No params.
			}
		}
		return result;
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
