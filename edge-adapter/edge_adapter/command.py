"""
Domain model of commands sent to the aircraft and of their results.

Like telemetry.py, this module knows nothing about MAVLink, RabbitMQ or JSON
and must not import any external library. The JSON contract lives in
command_schema.py (see docs/contracts/command-v1.md), the MAVLink side in
command_executor.py.
"""

import enum
import math
from dataclasses import dataclass, field
from datetime import datetime


class CommandType(enum.Enum):
    RTL = "RTL"
    RESUME_MISSION = "RESUME_MISSION"            # flight mode AUTO
    LOITER = "LOITER"                            # flight mode LOITER
    GOTO = "GOTO"                                # params: lat, lon, alt (metres above home)
    # params: waypoints ([[lat, lon, alt], ...], alt in metres above home, the last one is the
    # target); uploaded as the mission (replacing the one on the aircraft) and flown in AUTO.
    ROUTE = "ROUTE"
    # params: waypoints (as for ROUTE), loop (true: DO_JUMP back to the first waypoint, default false),
    # startSeq (the mission item flown first, 1..len(waypoints), default 1); uploaded as the mission
    # (replacing the one on the aircraft) and flown in AUTO from startSeq.
    MISSION_UPLOAD = "MISSION_UPLOAD"
    # params: approach ([lat, lon, alt], alt in metres above home), land ([lat, lon]); uploaded as the
    # mission [home, DO_LAND_START, approach waypoint, NAV_LAND] and flown in AUTO from DO_LAND_START.
    LAND = "LAND"
    TAKEOFF = "TAKEOFF"                          # params: alt (30..300 m above home, default 60)
    DISARM = "DISARM"                            # stop the motors; refused by the autopilot in flight
    # params: version, action ("RTL" | "REPORT"), polygons ([[[lat, lon], ...], ...]);
    # replaces the autopilot's exclusion fences and turns the polygon fence on.
    FENCE_UPLOAD = "FENCE_UPLOAD"
    # Simulator faults; they only work against SITL, whose SIM_* parameters they change.
    FAULT_BATTERY_DRAIN = "FAULT_BATTERY_DRAIN"
    FAULT_GPS_LOSS = "FAULT_GPS_LOSS"            # params: enabled (true = GPS lost)
    FAULT_LOW_ALTITUDE = "FAULT_LOW_ALTITUDE"
    FAULT_LINK_DROP = "FAULT_LINK_DROP"          # params: seconds (1..60)
    FAULT_RESET = "FAULT_RESET"


class CommandStatus(enum.Enum):
    ACCEPTED = "ACCEPTED"    # the aircraft confirmed the command (or it was applied locally)
    REJECTED = "REJECTED"    # invalid command, unknown aircraft, or refused by the aircraft
    FAILED = "FAILED"        # could not be carried out (link lost, parameter not applied)
    EXPIRED = "EXPIRED"      # issued too long ago; not carried out
    TIMEOUT = "TIMEOUT"      # the aircraft did not answer in time


LINK_DROP_MIN_S = 1
LINK_DROP_MAX_S = 60
TAKEOFF_MIN_ALT_M = 30
TAKEOFF_MAX_ALT_M = 300
TAKEOFF_DEFAULT_ALT_M = 60
ROUTE_MAX_WAYPOINTS = 100
FENCE_ACTIONS = ("RTL", "REPORT")
FENCE_MIN_VERTICES = 3          # per polygon
FENCE_MAX_TOTAL_VERTICES = 500  # all polygons together: one fence item per vertex
FENCE_VERSION_MAX_LENGTH = 64


@dataclass(frozen=True)
class Command:
    command_id: str
    drone_id: int
    type: CommandType
    params: dict = field(default_factory=dict)  # validated and normalized, see validate_params()
    issued_at: datetime | None = None           # timezone-aware


@dataclass(frozen=True)
class CommandResult:
    command_id: str | None   # None only when the command did not carry a usable commandId
    drone_id: int
    status: CommandStatus
    detail: str
    completed_at: datetime   # timezone-aware


class InvalidParams(ValueError):
    """The params of a command are missing or out of range."""


def validate_params(command_type, params):
    """Check the params of one command type and return them normalized.

    Raises InvalidParams with a human-readable reason. Unknown extra keys are
    ignored, so newer senders can add optional params.
    """
    if not isinstance(params, dict):
        raise InvalidParams("params must be an object")

    if command_type is CommandType.GOTO:
        lat = _number(params, "lat")
        lon = _number(params, "lon")
        alt = _number(params, "alt")
        if not -90 <= lat <= 90:
            raise InvalidParams(f"lat must be between -90 and 90, was {lat}")
        if not -180 <= lon <= 180:
            raise InvalidParams(f"lon must be between -180 and 180, was {lon}")
        if alt < 0:
            raise InvalidParams(f"alt must not be negative, was {alt}")
        return {"lat": lat, "lon": lon, "alt": alt}

    if command_type is CommandType.TAKEOFF:
        alt = _number(params, "alt") if "alt" in params else float(TAKEOFF_DEFAULT_ALT_M)
        if not TAKEOFF_MIN_ALT_M <= alt <= TAKEOFF_MAX_ALT_M:
            raise InvalidParams(f"alt must be between {TAKEOFF_MIN_ALT_M} and {TAKEOFF_MAX_ALT_M}, was {alt}")
        return {"alt": alt}

    if command_type is CommandType.FENCE_UPLOAD:
        return _fence_upload(params)

    if command_type is CommandType.ROUTE:
        return _route(params)

    if command_type is CommandType.MISSION_UPLOAD:
        return _mission_upload(params)

    if command_type is CommandType.LAND:
        return _land(params)

    if command_type is CommandType.FAULT_GPS_LOSS:
        enabled = params.get("enabled")
        if not isinstance(enabled, bool):
            raise InvalidParams("enabled (true or false) is required")
        return {"enabled": enabled}

    if command_type is CommandType.FAULT_LINK_DROP:
        seconds = _number(params, "seconds")
        if not LINK_DROP_MIN_S <= seconds <= LINK_DROP_MAX_S:
            raise InvalidParams(f"seconds must be between {LINK_DROP_MIN_S} and {LINK_DROP_MAX_S}, was {seconds}")
        return {"seconds": seconds}

    # The other types take no params.
    return {}


def _fence_upload(params):
    version = params.get("version")
    if not isinstance(version, str) or not version.strip() or len(version) > FENCE_VERSION_MAX_LENGTH:
        raise InvalidParams(f"version (a non-empty string of at most {FENCE_VERSION_MAX_LENGTH} characters) "
                            "is required")
    action = params.get("action")
    if action not in FENCE_ACTIONS:
        raise InvalidParams(f"action must be one of {', '.join(FENCE_ACTIONS)}, was {action!r}")
    polygons = params.get("polygons")
    if not isinstance(polygons, list):
        raise InvalidParams("polygons (a list of polygons, each a list of [lat, lon]) is required")
    result = []
    total = 0
    for i, polygon in enumerate(polygons):
        if not isinstance(polygon, list):
            raise InvalidParams(f"polygons[{i}] must be a list of [lat, lon]")
        vertices = [_vertex(vertex, f"polygons[{i}][{j}]") for j, vertex in enumerate(polygon)]
        # A closed ring (last vertex = first) is accepted; the fence protocol wants it open.
        if len(vertices) > 1 and vertices[0] == vertices[-1]:
            vertices.pop()
        if len(vertices) < FENCE_MIN_VERTICES:
            raise InvalidParams(f"polygons[{i}] needs at least {FENCE_MIN_VERTICES} distinct vertices")
        total += len(vertices)
        result.append(vertices)
    if total > FENCE_MAX_TOTAL_VERTICES:
        raise InvalidParams(f"at most {FENCE_MAX_TOTAL_VERTICES} vertices in total, was {total}")
    return {"version": version, "action": action, "polygons": result}


def _route(params):
    waypoints = params.get("waypoints")
    if not isinstance(waypoints, list) or not 1 <= len(waypoints) <= ROUTE_MAX_WAYPOINTS:
        raise InvalidParams(f"waypoints (1 to {ROUTE_MAX_WAYPOINTS} points [lat, lon, alt]) is required")
    result = []
    for i, point in enumerate(waypoints):
        if (not isinstance(point, list) or len(point) != 3
                or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in point)):
            raise InvalidParams(f"waypoints[{i}] must be [lat, lon, alt]")
        lat, lon, alt = (float(v) for v in point)
        if not -90 <= lat <= 90 or not -180 <= lon <= 180:
            raise InvalidParams(f"waypoints[{i}] is out of range: [{lat}, {lon}]")
        if alt < 0:
            raise InvalidParams(f"waypoints[{i}] alt must not be negative, was {alt}")
        result.append((lat, lon, alt))
    return {"waypoints": result}


def _mission_upload(params):
    waypoints = _route(params)["waypoints"]
    loop = params.get("loop", False)
    if not isinstance(loop, bool):
        raise InvalidParams("loop must be true or false")
    start_seq = params.get("startSeq", 1)
    # An integral float (3.0) is accepted, like the other numbers of the contract.
    if (isinstance(start_seq, bool) or not isinstance(start_seq, (int, float)) or not math.isfinite(start_seq)
            or start_seq != int(start_seq) or not 1 <= start_seq <= len(waypoints)):
        raise InvalidParams(f"startSeq must be an integer between 1 and {len(waypoints)}, was {start_seq!r}")
    return {"waypoints": waypoints, "loop": loop, "startSeq": int(start_seq)}


def _land(params):
    approach = params.get("approach")
    if (not isinstance(approach, list) or len(approach) != 3
            or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in approach)):
        raise InvalidParams("approach must be [lat, lon, alt]")
    lat, lon, alt = (float(v) for v in approach)
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise InvalidParams(f"approach is out of range: [{lat}, {lon}]")
    if alt < 0:
        raise InvalidParams(f"approach alt must not be negative, was {alt}")
    land = _vertex(params.get("land"), "land")
    return {"approach": (lat, lon, alt), "land": land}


def _vertex(value, name):
    if (not isinstance(value, list) or len(value) != 2
            or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in value)):
        raise InvalidParams(f"{name} must be [lat, lon]")
    lat, lon = float(value[0]), float(value[1])
    if not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise InvalidParams(f"{name} is out of range: [{lat}, {lon}]")
    return (lat, lon)


def _number(params, name):
    value = params.get(name)
    # bool is an int subclass in Python, but true/false is not a number here.
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise InvalidParams(f"{name} (a number) is required")
    return float(value)
