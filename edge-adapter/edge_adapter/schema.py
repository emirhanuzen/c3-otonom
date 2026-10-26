"""
Turns a DroneTelemetry into the JSON snapshot published to RabbitMQ.

This is the wire format seen by consumers. It only depends on the domain
model (telemetry.py), never on MAVLink. Units are already metres, m/s,
degrees and volts; unknown values become null.
"""

import json
from datetime import timezone

SCHEMA_VERSION = 2

# Decimal places kept in the snapshot. 7 decimals of a degree is ~1 cm,
# the precision MAVLink itself sends lat/lon with.
LATLON_DIGITS = 7
ALTITUDE_DIGITS = 2
SPEED_DIGITS = 2
VOLTAGE_DIGITS = 2
HEADING_DIGITS = 1


def _round(value, digits):
    return None if value is None else round(value, digits)


def format_timestamp(timestamp):
    """Timezone-aware datetime -> UTC ISO 8601 with milliseconds, e.g. 2026-10-24T12:34:56.789Z."""
    return timestamp.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def build_snapshot(telemetry, session_id, seq, timestamp):
    """Build the snapshot dictionary of one aircraft.

    session_id is generated once per adapter run by the caller, so tests can
    pass a fixed value.
    """
    position = telemetry.position
    velocity = telemetry.velocity
    return {
        "schemaVersion": SCHEMA_VERSION,
        "droneId": telemetry.drone_id,
        "sessionId": session_id,
        "seq": seq,
        "timestamp": format_timestamp(timestamp),
        "position": {
            "lat": _round(position.lat, LATLON_DIGITS),
            "lon": _round(position.lon, LATLON_DIGITS),
            "altMsl": _round(position.alt_msl, ALTITUDE_DIGITS),
            "altRel": _round(position.alt_rel, ALTITUDE_DIGITS),
            "heading": _round(position.heading, HEADING_DIGITS),
        },
        "velocity": {
            "groundSpeed": _round(velocity.ground_speed, SPEED_DIGITS),
            "airSpeed": _round(velocity.air_speed, SPEED_DIGITS),
            "climbRate": _round(velocity.climb_rate, SPEED_DIGITS),
        },
        "battery": {
            "percent": telemetry.battery.percent,
            "voltage": _round(telemetry.battery.voltage, VOLTAGE_DIGITS),
        },
        "gps": {
            "fixType": telemetry.gps.fix_type,
            "satellites": telemetry.gps.satellites,
        },
        "status": {
            "mode": telemetry.status.mode,
            "armed": telemetry.status.armed,
        },
    }


def to_json(snapshot):
    """Serialize a snapshot to compact UTF-8 JSON bytes, ready to publish."""
    return json.dumps(snapshot, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
