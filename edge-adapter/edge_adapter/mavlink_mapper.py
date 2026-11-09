"""
Converts the latest MAVLink messages of one aircraft into DroneTelemetry.

All MAVLink-specific knowledge lives here: scaled integer units, the
"unknown" sentinel values and how flight mode and arming state are encoded.
Fields whose message has not arrived yet stay None.
"""

from pymavlink import mavutil

from edge_adapter.telemetry import Battery, DroneTelemetry, Gps, Home, Position, Status, Velocity

# MAVLink uses these values to say "unknown" in some fields.
UINT8_UNKNOWN = 255
UINT16_UNKNOWN = 65535


def to_telemetry(drone_id, state):
    """Build the DroneTelemetry of one aircraft from its DroneState."""
    return DroneTelemetry(
        drone_id=drone_id,
        position=_position(state.messages.get("GLOBAL_POSITION_INT")),
        velocity=_velocity(state.messages.get("VFR_HUD")),
        battery=_battery(state.messages.get("SYS_STATUS")),
        gps=_gps(state.messages.get("GPS_RAW_INT")),
        status=_status(state.messages.get("HEARTBEAT"), state.messages.get("FENCE_STATUS"),
                       state.messages.get("MISSION_CURRENT")),
        home=_home(state.messages.get("HOME_POSITION")),
    )


def _home(msg):
    if msg is None:
        return Home()
    # latitude/longitude are degrees * 10^7, altitude millimetres above mean sea level.
    return Home(lat=msg.latitude / 1e7, lon=msg.longitude / 1e7, alt_msl=msg.altitude / 1000)


def _position(msg):
    if msg is None:
        return Position()
    return Position(
        # lat/lon are degrees * 10^7.
        lat=msg.lat / 1e7,
        lon=msg.lon / 1e7,
        # alt is millimetres above mean sea level, relative_alt millimetres above home.
        alt_msl=msg.alt / 1000,
        alt_rel=msg.relative_alt / 1000,
        # hdg is centidegrees (0..35999), 65535 means unknown.
        heading=msg.hdg / 100 if msg.hdg != UINT16_UNKNOWN else None,
    )


def _velocity(msg):
    if msg is None:
        return Velocity()
    # VFR_HUD already uses m/s for all three. climb is positive when going up.
    return Velocity(
        ground_speed=msg.groundspeed,
        air_speed=msg.airspeed,
        climb_rate=msg.climb,
    )


def _battery(msg):
    if msg is None:
        return Battery()
    return Battery(
        # battery_remaining is a percentage, -1 means unknown.
        percent=msg.battery_remaining if msg.battery_remaining >= 0 else None,
        # voltage_battery is millivolts, 65535 means unknown.
        voltage=msg.voltage_battery / 1000 if msg.voltage_battery != UINT16_UNKNOWN else None,
    )


def _gps(msg):
    if msg is None:
        return Gps()
    return Gps(
        # fix_type is the raw MAVLink GPS_FIX_TYPE value, same numbering as Gps.fix_type.
        fix_type=msg.fix_type,
        # satellites_visible is 255 when unknown.
        satellites=msg.satellites_visible if msg.satellites_visible != UINT8_UNKNOWN else None,
    )


def _status(heartbeat, fence, mission_current=None):
    # FENCE_STATUS only arrives while the autopilot's fence is enabled; without it both stay None.
    fence_breached = bool(fence.breach_status) if fence is not None else None
    fence_breach_count = fence.breach_count if fence is not None else None
    # MISSION_CURRENT.seq: the mission item the autopilot flies to now (0 = home, 1 = first waypoint).
    mission_seq = mission_current.seq if mission_current is not None else None
    if heartbeat is None:
        return Status(fence_breached=fence_breached, fence_breach_count=fence_breach_count, mission_seq=mission_seq)
    return Status(
        # The mode name comes from custom_mode, interpreted per vehicle type.
        mode=mavutil.mode_string_v10(heartbeat),
        armed=bool(heartbeat.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED),
        fence_breached=fence_breached,
        fence_breach_count=fence_breach_count,
        mission_seq=mission_seq,
    )
