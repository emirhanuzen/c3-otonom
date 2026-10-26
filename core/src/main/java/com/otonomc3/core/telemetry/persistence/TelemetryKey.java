package com.otonomc3.core.telemetry.persistence;

import java.util.UUID;

/** Natural key of a telemetry row: the unique (drone_id, session_id, seq) triple. */
public record TelemetryKey(int droneId, UUID sessionId, long seq) {
}
