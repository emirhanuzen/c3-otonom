package com.otonomc3.core.drone.service;

import java.time.Duration;
import java.time.Instant;

import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;

/**
 * Live state of one drone at the moment it was read.
 *
 * @param message    the newest accepted telemetry message
 * @param lastSeenAt when that message was received
 * @param age        time since lastSeenAt
 * @param linkStatus derived from age
 */
public record DroneLiveState(TelemetryMessageDto message, Instant lastSeenAt, Duration age, LinkStatus linkStatus) {

	public int droneId() {
		return message.droneId();
	}

}
