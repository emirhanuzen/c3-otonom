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


def _number(params, name):
    value = params.get(name)
    # bool is an int subclass in Python, but true/false is not a number here.
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise InvalidParams(f"{name} (a number) is required")
    return float(value)
