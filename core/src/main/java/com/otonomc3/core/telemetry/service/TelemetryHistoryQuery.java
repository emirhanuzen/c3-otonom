package com.otonomc3.core.telemetry.service;

import java.time.Duration;
import java.time.Instant;

/**
 * A validated telemetry history query: the [from, to] range (both inclusive) and
 * the maximum number of rows.
 */
public record TelemetryHistoryQuery(Instant from, Instant to, int limit) {

	public static final Duration DEFAULT_RANGE = Duration.ofMinutes(5);
	public static final Duration MAX_RANGE = Duration.ofHours(1);
	public static final int DEFAULT_LIMIT = 1000;
	public static final int MAX_LIMIT = 5000;

	/**
	 * Fills in the defaults and checks the limits. to defaults to now, from to
	 * five minutes before to, limit to 1000.
	 *
	 * @throws InvalidTelemetryQueryException when from is not before to, the range is
	 *                                        longer than one hour, or limit is not in 1..5000
	 */
	public static TelemetryHistoryQuery resolve(Instant from, Instant to, Integer limit, Instant now) {
		Instant end = to != null ? to : now;
		Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
		int max = limit != null ? limit : DEFAULT_LIMIT;

		if (max < 1 || max > MAX_LIMIT) {
			throw new InvalidTelemetryQueryException("limit must be between 1 and " + MAX_LIMIT + ", was " + max);
		}
		if (!start.isBefore(end)) {
			throw new InvalidTelemetryQueryException("from (" + start + ") must be before to (" + end + ")");
		}
		if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
			throw new InvalidTelemetryQueryException("The range from " + start + " to " + end
					+ " is longer than the maximum of " + MAX_RANGE.toMinutes() + " minutes");
		}
		return new TelemetryHistoryQuery(start, end, max);
	}

}
