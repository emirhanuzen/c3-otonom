"""
Turns the latest MAVLink messages of one aircraft into a JSON snapshot.

Units in the snapshot: metres, m/s, degrees and volts. Fields whose message
has not arrived yet, or that the autopilot reports as "unknown", are null.
"""

import json
from datetime import datetime, timezone

from pymavlink import mavutil

SCHEMA_VERSION = 1

# MAVLink uses these values to say "unknown" in some fields.
UINT8_UNKNOWN = 255
UINT16_UNKNOWN = 65535


def _utc_timestamp():
    """Current UTC time as ISO 8601 with milliseconds, e.g. 2026-10-24T12:34:56.789Z."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def build_snapshot(drone_id, state, seq):
    """Build the snapshot dictionary of one aircraft from its DroneState."""
    heartbeat = state.messages.get("HEARTBEAT")
    position = state.messages.get("GLOBAL_POSITION_INT")
    hud = state.messages.get("VFR_HUD")
    sys_status = state.messages.get("SYS_STATUS")
    gps = state.messages.get("GPS_RAW_INT")

    snapshot = {
        "schemaVersion": SCHEMA_VERSION,
        "droneId": drone_id,
        "seq": seq,
        "timestamp": _utc_timestamp(),
        "position": {"lat": None, "lon": None, "altMsl": None, "altRel": None, "heading": None},
        "velocity": {"groundSpeed": None, "airSpeed": None, "climbRate": None},
        "battery": {"percent": None, "voltage": None},
        "gps": {"fixType": None, "satellites": None},
        "status": {"mode": None, "armed": None},
    }

    if position is not None:
        p = snapshot["position"]
        # lat/lon are degrees * 10^7.
        p["lat"] = position.lat / 1e7
        p["lon"] = position.lon / 1e7
        # alt is millimetres above mean sea level, relative_alt millimetres above home.
        p["altMsl"] = position.alt / 1000
        p["altRel"] = position.relative_alt / 1000
        # hdg is centidegrees (0..35999), 65535 means unknown.
        p["heading"] = position.hdg / 100 if position.hdg != UINT16_UNKNOWN else None

    if hud is not None:
        v = snapshot["velocity"]
        # VFR_HUD already uses m/s for all three. climb is positive when going up.
        v["groundSpeed"] = hud.groundspeed
        v["airSpeed"] = hud.airspeed
        v["climbRate"] = hud.climb

    if sys_status is not None:
        b = snapshot["battery"]
        # battery_remaining is a percentage, -1 means unknown.
        b["percent"] = sys_status.battery_remaining if sys_status.battery_remaining >= 0 else None
        # voltage_battery is millivolts, 65535 means unknown.
        b["voltage"] = (sys_status.voltage_battery / 1000
                        if sys_status.voltage_battery != UINT16_UNKNOWN else None)

    if gps is not None:
        g = snapshot["gps"]
        # fix_type is the raw MAVLink GPS_FIX_TYPE value:
        # 0 no GPS, 1 no fix, 2 2D, 3 3D, 4 DGPS, 5 RTK float, 6 RTK fixed, 7 static, 8 PPP.
        g["fixType"] = gps.fix_type
        # satellites_visible is 255 when unknown.
        g["satellites"] = gps.satellites_visible if gps.satellites_visible != UINT8_UNKNOWN else None

    if heartbeat is not None:
        s = snapshot["status"]
        s["mode"] = mavutil.mode_string_v10(heartbeat)
        s["armed"] = bool(heartbeat.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED)

    return snapshot


def to_json(snapshot):
    """Serialize a snapshot to compact UTF-8 JSON bytes, ready to publish."""
    return json.dumps(snapshot, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
