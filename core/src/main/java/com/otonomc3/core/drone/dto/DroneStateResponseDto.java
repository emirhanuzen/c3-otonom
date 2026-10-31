package com.otonomc3.core.drone.dto;

import java.time.Instant;
import java.util.UUID;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;

/**
 * Live state of one drone as returned by the REST API.
 *
 * @param lastSeenAt when the newest message was received
 * @param ageMs      milliseconds since lastSeenAt
 * @param timestamp  the newest message's own timestamp, set by the edge adapter
 */
public record DroneStateResponseDto(
		Integer droneId,
		LinkStatus linkStatus,
		Instant lastSeenAt,
		long ageMs,
		UUID sessionId,
		Long seq,
		Instant timestamp,
		TelemetryMessageDto.Position position,
		TelemetryMessageDto.Velocity velocity,
		TelemetryMessageDto.Battery battery,
		TelemetryMessageDto.Gps gps,
		TelemetryMessageDto.Status status) {

	public static DroneStateResponseDto from(DroneLiveState state) {
		TelemetryMessageDto message = state.message();
		return new DroneStateResponseDto(
				message.droneId(),
				state.linkStatus(),
				state.lastSeenAt(),
				state.age().toMillis(),
				message.sessionId(),
				message.seq(),
				message.timestamp(),
				message.position(),
				message.velocity(),
				message.battery(),
				message.gps(),
				message.status());
	}

}
