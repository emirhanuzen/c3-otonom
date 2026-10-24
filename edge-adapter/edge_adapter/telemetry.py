"""
Domain model of the edge adapter: the telemetry of one aircraft.

This module knows nothing about MAVLink, RabbitMQ or JSON, and must not
import any external library. Protocol-specific code converts into these
classes (mavlink_mapper.py) or out of them (schema.py).

Units: metres, m/s, degrees and volts. None means "unknown / not received yet".
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Position:
    lat: float | None = None       # degrees, WGS84
    lon: float | None = None       # degrees, WGS84
    alt_msl: float | None = None   # metres above mean sea level
    alt_rel: float | None = None   # metres above the home position
    heading: float | None = None   # degrees, 0..360, 0 = north


@dataclass(frozen=True)
class Velocity:
    ground_speed: float | None = None  # m/s
    air_speed: float | None = None     # m/s
    climb_rate: float | None = None    # m/s, positive when going up


@dataclass(frozen=True)
class Battery:
    percent: int | None = None     # remaining charge, 0..100
    voltage: float | None = None   # volts


@dataclass(frozen=True)
class Gps:
    # 0 no GPS, 1 no fix, 2 2D, 3 3D, 4 DGPS, 5 RTK float, 6 RTK fixed, 7 static, 8 PPP.
    fix_type: int | None = None
    satellites: int | None = None


@dataclass(frozen=True)
class Status:
    mode: str | None = None    # flight mode name, e.g. "AUTO"
    armed: bool | None = None


@dataclass(frozen=True)
class DroneTelemetry:
    drone_id: int
    position: Position = field(default_factory=Position)
    velocity: Velocity = field(default_factory=Velocity)
    battery: Battery = field(default_factory=Battery)
    gps: Gps = field(default_factory=Gps)
    status: Status = field(default_factory=Status)
