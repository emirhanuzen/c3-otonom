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
		Status status) {

	public static final int SCHEMA_VERSION = 2;

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

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Status(String mode, Boolean armed) {
	}

}
