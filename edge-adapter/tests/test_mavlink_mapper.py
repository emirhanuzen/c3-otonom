"""Tests of mavlink_mapper.py: fake MAVLink messages -> DroneTelemetry."""

import pytest
from pymavlink import mavutil

from edge_adapter.mavlink_mapper import to_telemetry
from edge_adapter.mavlink_reader import DroneState
from edge_adapter.telemetry import Battery, Gps, Position, Status, Velocity

mavlink = mavutil.mavlink

# ArduPlane custom_mode 10 is AUTO.
PLANE_AUTO = 10


def heartbeat(armed=True, custom_mode=PLANE_AUTO):
    base_mode = mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
    if armed:
        base_mode |= mavlink.MAV_MODE_FLAG_SAFETY_ARMED
    return mavlink.MAVLink_heartbeat_message(
        type=mavlink.MAV_TYPE_FIXED_WING,
        autopilot=mavlink.MAV_AUTOPILOT_ARDUPILOTMEGA,
        base_mode=base_mode,
        custom_mode=custom_mode,
        system_status=mavlink.MAV_STATE_ACTIVE,
        mavlink_version=3,
    )


def global_position_int(hdg=8755):
    return mavlink.MAVLink_global_position_int_message(
        time_boot_ms=1000,
        lat=386063123,         # 38.6063123 degrees
        lon=392914123,         # 39.2914123 degrees
        alt=1012345,           # 1012.345 m MSL
        relative_alt=100250,   # 100.25 m above home
        vx=0, vy=0, vz=0,
        hdg=hdg,               # centidegrees
    )


def vfr_hud():
    return mavlink.MAVLink_vfr_hud_message(
        airspeed=21.5, groundspeed=22.25, heading=87, throttle=60, alt=1012.3, climb=-0.75,
    )


def sys_status(voltage_battery=12587, battery_remaining=97):
    return mavlink.MAVLink_sys_status_message(
        onboard_control_sensors_present=0,
        onboard_control_sensors_enabled=0,
        onboard_control_sensors_health=0,
        load=0,
        voltage_battery=voltage_battery,  # millivolts
        current_battery=-1,
        battery_remaining=battery_remaining,
        drop_rate_comm=0,
        errors_comm=0,
        errors_count1=0, errors_count2=0, errors_count3=0, errors_count4=0,
    )


def gps_raw_int(satellites_visible=10, fix_type=3):
    return mavlink.MAVLink_gps_raw_int_message(
        time_usec=0, fix_type=fix_type, lat=386063123, lon=392914123, alt=1012345,
        eph=100, epv=100, vel=2225, cog=8755, satellites_visible=satellites_visible,
    )


def state_with(*messages):
    state = DroneState()
    for msg in messages:
        state.messages[msg.get_type()] = msg
    return state


def test_no_messages_gives_all_none():
    telemetry = to_telemetry(5, DroneState())
    assert telemetry.drone_id == 5
    assert telemetry.position == Position()
    assert telemetry.velocity == Velocity()
    assert telemetry.battery == Battery()
    assert telemetry.gps == Gps()
    assert telemetry.status == Status()


def test_unit_conversions():
    telemetry = to_telemetry(1, state_with(
        heartbeat(), global_position_int(), vfr_hud(), sys_status(), gps_raw_int(),
    ))

    # degrees * 10^7 -> degrees, millimetres -> metres, centidegrees -> degrees
    assert telemetry.position.lat == pytest.approx(38.6063123)
    assert telemetry.position.lon == pytest.approx(39.2914123)
    assert telemetry.position.alt_msl == pytest.approx(1012.345)
    assert telemetry.position.alt_rel == pytest.approx(100.25)
    assert telemetry.position.heading == pytest.approx(87.55)

    # VFR_HUD is already in m/s
    assert telemetry.velocity.ground_speed == pytest.approx(22.25)
    assert telemetry.velocity.air_speed == pytest.approx(21.5)
    assert telemetry.velocity.climb_rate == pytest.approx(-0.75)

    # millivolts -> volts
    assert telemetry.battery.voltage == pytest.approx(12.587)
    assert telemetry.battery.percent == 97

    assert telemetry.gps.fix_type == 3
    assert telemetry.gps.satellites == 10

    assert telemetry.status.mode == "AUTO"
    assert telemetry.status.armed is True


def test_disarmed():
    telemetry = to_telemetry(1, state_with(heartbeat(armed=False)))
    assert telemetry.status.armed is False
    assert telemetry.status.mode == "AUTO"


def test_unknown_heading_65535_is_none():
    telemetry = to_telemetry(1, state_with(global_position_int(hdg=65535)))
    assert telemetry.position.heading is None
    # The other position fields are still converted.
    assert telemetry.position.lat == pytest.approx(38.6063123)


def test_unknown_voltage_65535_is_none():
    telemetry = to_telemetry(1, state_with(sys_status(voltage_battery=65535)))
    assert telemetry.battery.voltage is None
    assert telemetry.battery.percent == 97


def test_unknown_battery_percent_minus_one_is_none():
    telemetry = to_telemetry(1, state_with(sys_status(battery_remaining=-1)))
    assert telemetry.battery.percent is None


def test_unknown_satellites_255_is_none():
    telemetry = to_telemetry(1, state_with(gps_raw_int(satellites_visible=255)))
    assert telemetry.gps.satellites is None
    assert telemetry.gps.fix_type == 3
