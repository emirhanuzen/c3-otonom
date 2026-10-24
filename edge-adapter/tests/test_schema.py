"""Tests of schema.py: DroneTelemetry -> JSON snapshot."""

import json
from datetime import datetime, timedelta, timezone

from edge_adapter.schema import SCHEMA_VERSION, build_snapshot, to_json
from edge_adapter.telemetry import Battery, DroneTelemetry, Gps, Position, Status, Velocity

TIMESTAMP = datetime(2026, 10, 24, 12, 34, 56, 789123, tzinfo=timezone.utc)


def full_telemetry():
    return DroneTelemetry(
        drone_id=3,
        position=Position(lat=38.606312345678, lon=39.291412345678,
                          alt_msl=1012.3456, alt_rel=100.2049, heading=87.56),
        velocity=Velocity(ground_speed=22.1234, air_speed=21.8765, climb_rate=-0.3333),
        battery=Battery(percent=97, voltage=12.5876),
        gps=Gps(fix_type=3, satellites=10),
        status=Status(mode="AUTO", armed=True),
    )


def snapshot_of(telemetry, seq=42, timestamp=TIMESTAMP):
    # Go through the real JSON bytes, so we test what consumers actually receive.
    return json.loads(to_json(build_snapshot(telemetry, seq, timestamp)))


def test_top_level_fields():
    snap = snapshot_of(full_telemetry())
    assert snap["schemaVersion"] == SCHEMA_VERSION == 1
    assert snap["droneId"] == 3
    assert snap["seq"] == 42
    assert snap["timestamp"] == "2026-10-24T12:34:56.789Z"
    assert set(snap) == {"schemaVersion", "droneId", "seq", "timestamp",
                         "position", "velocity", "battery", "gps", "status"}


def test_timestamp_is_converted_to_utc():
    istanbul = timezone(timedelta(hours=3))
    snap = snapshot_of(full_telemetry(), timestamp=datetime(2026, 10, 24, 15, 0, 0, tzinfo=istanbul))
    assert snap["timestamp"] == "2026-10-24T12:00:00.000Z"


def test_values_are_rounded():
    snap = snapshot_of(full_telemetry())
    assert snap["position"] == {
        "lat": 38.6063123,        # 7 decimals
        "lon": 39.2914123,        # 7 decimals
        "altMsl": 1012.35,        # 2 decimals
        "altRel": 100.2,          # 2 decimals (100.20)
        "heading": 87.6,          # 1 decimal
    }
    assert snap["velocity"] == {"groundSpeed": 22.12, "airSpeed": 21.88, "climbRate": -0.33}
    assert snap["battery"] == {"percent": 97, "voltage": 12.59}
    assert snap["gps"] == {"fixType": 3, "satellites": 10}
    assert snap["status"] == {"mode": "AUTO", "armed": True}


def test_unknown_values_are_null():
    snap = snapshot_of(DroneTelemetry(drone_id=7))
    assert snap["droneId"] == 7
    assert snap["position"] == {"lat": None, "lon": None, "altMsl": None, "altRel": None, "heading": None}
    assert snap["velocity"] == {"groundSpeed": None, "airSpeed": None, "climbRate": None}
    assert snap["battery"] == {"percent": None, "voltage": None}
    assert snap["gps"] == {"fixType": None, "satellites": None}
    assert snap["status"] == {"mode": None, "armed": None}
    # null in the JSON text itself, not just a missing key.
    assert b'"lat":null' in to_json(build_snapshot(DroneTelemetry(drone_id=7), 0, TIMESTAMP))


def test_armed_false_is_not_null():
    snap = snapshot_of(DroneTelemetry(drone_id=1, status=Status(mode="MANUAL", armed=False)))
    assert snap["status"] == {"mode": "MANUAL", "armed": False}
