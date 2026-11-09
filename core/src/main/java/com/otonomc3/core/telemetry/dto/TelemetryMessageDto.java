package com.otonomc3.core.telemetry.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One telemetry snapshot as published by the edge adapter (schema.py), schema version 2.
 *
 * <p>Units are metres, m/s, degrees and volts. Any field may be null when the
 * value is unknown; unknown JSON fields are ignored so newer publishers can add fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryMessageDto(
		Integer schemaVersion,
		UUID sessionId,
		Integer droneId,
		Long seq,
		Instant timestamp,
		Position position,
		Velocity velocity,
		Battery battery,
		Gps gps,
		Status status,
		Home home) {

	public static final int SCHEMA_VERSION = 2;

	/** Without the home (unknown). */
	public TelemetryMessageDto(Integer schemaVersion, UUID sessionId, Integer droneId, Long seq, Instant timestamp,
			Position position, Velocity velocity, Battery battery, Gps gps, Status status) {
		this(schemaVersion, sessionId, droneId, seq, timestamp, position, velocity, battery, gps, status, null);
	}

	/** The autopilot's home (MAVLink HOME_POSITION); the fields are null until the autopilot sent it. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Home(Double lat, Double lon, Double altMsl) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Position(Double lat, Double lon, Double altMsl, Double altRel, Double heading) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Velocity(Double groundSpeed, Double airSpeed, Double climbRate) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Battery(Integer percent, Double voltage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Gps(Integer fixType, Integer satellites) {
	}

	/**
	 * @param fenceBreached    the autopilot's own (onboard) fence is breached now; from MAVLink FENCE_STATUS,
	 *                         which only arrives while that fence is enabled
	 * @param fenceBreachCount breaches the autopilot counted since it booted
	 * @param missionSeq       the mission item the autopilot flies to now (MAVLink MISSION_CURRENT.seq;
	 *                         0 is the home item, 1 the first waypoint)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Status(String mode, Boolean armed, Boolean fenceBreached, Integer fenceBreachCount,
			Integer missionSeq) {

		/** Without the onboard fence fields and the mission item (unknown). */
		public Status(String mode, Boolean armed) {
			this(mode, armed, null, null, null);
		}

		/** Without the mission item (unknown). */
		public Status(String mode, Boolean armed, Boolean fenceBreached, Integer fenceBreachCount) {
			this(mode, armed, fenceBreached, fenceBreachCount, null);
		}

	}

}
