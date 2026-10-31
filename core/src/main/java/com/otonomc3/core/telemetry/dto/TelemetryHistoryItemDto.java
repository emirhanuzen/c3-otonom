package com.otonomc3.core.telemetry.dto;

import java.time.Instant;
import java.util.UUID;

import com.otonomc3.core.telemetry.persistence.TelemetryEntity;
import org.locationtech.jts.geom.Point;

/**
 * One stored telemetry snapshot as returned by the history API. Units are the
 * same as in {@link TelemetryMessageDto}; lat and lon are null when the position is unknown.
 */
public record TelemetryHistoryItemDto(
		UUID sessionId,
		Long seq,
		Instant timestamp,
		Instant receivedAt,
		boolean late,
		Double lat,
		Double lon,
		Double altMsl,
		Double altRel,
		Double heading,
		Double groundSpeed,
		Double airSpeed,
		Double climbRate,
		Integer batteryPercent,
		Double batteryVoltage,
		Integer gpsFixType,
		Integer gpsSatellites,
		String mode,
		Boolean armed) {

	public static TelemetryHistoryItemDto from(TelemetryEntity entity) {
		// The stored point has x = longitude, y = latitude.
		Point position = entity.getPosition();
		Double lat = position == null || position.isEmpty() ? null : position.getY();
		Double lon = position == null || position.isEmpty() ? null : position.getX();
		return new TelemetryHistoryItemDto(
				entity.getSessionId(),
				entity.getSeq(),
				entity.getTimestamp(),
				entity.getReceivedAt(),
				entity.isLate(),
				lat,
				lon,
				entity.getAltMsl(),
				entity.getAltRel(),
				entity.getHeading(),
				entity.getGroundSpeed(),
				entity.getAirSpeed(),
				entity.getClimbRate(),
				entity.getBatteryPercent(),
				entity.getBatteryVoltage(),
				entity.getGpsFixType(),
				entity.getGpsSatellites(),
				entity.getMode(),
				entity.getArmed());
	}

}
