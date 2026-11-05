package com.otonomc3.core.alarm.service;

/**
 * A validated alarm list query.
 *
 * @param active  true for active alarms, false for cleared ones
 * @param droneId only this drone's alarms; null for all drones
 * @param limit   maximum number of alarms
 */
public record AlarmQuery(boolean active, Integer droneId, int limit) {

	public static final int DEFAULT_LIMIT = 100;
	public static final int MAX_LIMIT = 500;

	/**
	 * Fills in the default limit (100) and checks it.
	 *
	 * @throws InvalidAlarmQueryException when limit is not in 1..500
	 */
	public static AlarmQuery resolve(boolean active, Integer droneId, Integer limit) {
		int max = limit != null ? limit : DEFAULT_LIMIT;
		if (max < 1 || max > MAX_LIMIT) {
			throw new InvalidAlarmQueryException("limit must be between 1 and " + MAX_LIMIT + ", was " + max);
		}
		return new AlarmQuery(active, droneId, max);
	}

}
