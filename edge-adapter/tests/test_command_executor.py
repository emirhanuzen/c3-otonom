"""Tests of command_executor.py with a fake MAVLink connection and fake clocks."""

import json
import math
from datetime import datetime, timedelta, timezone

import pytest
from pymavlink import mavutil

from edge_adapter.command import CommandStatus
from edge_adapter.command_executor import CommandExecutor

mavlink = mavutil.mavlink

DRONE = 3
NOW = datetime(2026, 11, 5, 12, 0, 0, tzinfo=timezone.utc)
COMMAND_IDS = iter(f"00000000-0000-4000-8000-{i:012d}" for i in range(1, 1000))


class FakeMav:
    """Records every *_send call as (name, args)."""

    def __init__(self):
        self.sent = []

    def __getattr__(self, name):
        if not name.endswith("_send"):
            raise AttributeError(name)
        return lambda *args: self.sent.append((name[:-len("_send")], args))


class FakeConnection:
    def __init__(self):
        self.mav = FakeMav()


class FakeReader:
    def __init__(self, drones=(DRONE,)):
        self.connections = {drone: FakeConnection() for drone in drones}

    def connection_for(self, sysid):
        return self.connections.get(sysid)

    def sent(self, drone=DRONE):
        return self.connections[drone].mav.sent


class Clock:
    def __init__(self):
        self.monotonic = 100.0
        self.utc = NOW

    def advance(self, seconds):
        self.monotonic += seconds
        self.utc += timedelta(seconds=seconds)


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def reader():
    return FakeReader(drones=(DRONE, 4))


@pytest.fixture
def executor(reader, clock):
    return CommandExecutor(reader, monotonic=lambda: clock.monotonic, utcnow=lambda: clock.utc)


def command(command_type, params=None, drone=DRONE, issued_at=NOW, command_id=None):
    return json.dumps({
        "schemaVersion": 1,
        "commandId": command_id or next(COMMAND_IDS),
        "droneId": drone,
        "type": command_type,
        "params": params or {},
        "issuedAt": issued_at.isoformat().replace("+00:00", "Z"),
    }).encode()


def ack(command_id, result=mavlink.MAV_RESULT_ACCEPTED):
    return mavlink.MAVLink_command_ack_message(command=command_id, result=result)


def param_value(name, value):
    return mavlink.MAVLink_param_value_message(
        param_id=name.encode("ascii"), param_value=value, param_type=mavlink.MAV_PARAM_TYPE_REAL32,
        param_count=1000, param_index=1)


def single_result(executor):
    results = executor.take_results()
    assert len(results) == 1
    return results[0]


def sent_param_sets(reader, drone=DRONE):
    return [(args[2].decode(), args[3]) for name, args in reader.sent(drone) if name == "param_set"]


# --- Flight commands -------------------------------------------------------------

def test_rtl_is_accepted_on_ack(executor, reader):
    executor.handle_message(command("RTL"))

    assert reader.sent() == [("command_long", (DRONE, 1, mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH, 0,
                                               0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))]
    assert executor.take_results() == []

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))

    result = single_result(executor)
    assert result.status is CommandStatus.ACCEPTED
    assert result.drone_id == DRONE
    assert result.completed_at == NOW


def test_refused_ack_is_rejected(executor):
    executor.handle_message(command("RTL"))
    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH, mavlink.MAV_RESULT_DENIED))

    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert "MAV_RESULT_DENIED" in result.detail


def test_in_progress_ack_keeps_waiting(executor):
    executor.handle_message(command("RTL"))
    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH, mavlink.MAV_RESULT_IN_PROGRESS))
    assert executor.take_results() == []

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))
    assert single_result(executor).status is CommandStatus.ACCEPTED


def test_ack_of_another_command_or_drone_is_ignored(executor):
    executor.handle_message(command("RTL"))
    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_DO_SET_MODE))
    executor.on_mavlink(4, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))
    assert executor.take_results() == []


def test_no_reply_within_five_seconds_is_timeout(executor, clock):
    executor.handle_message(command("RTL"))
    clock.advance(4.9)
    executor.tick()
    assert executor.take_results() == []

    clock.advance(0.1)
    executor.tick()
    result = single_result(executor)
    assert result.status is CommandStatus.TIMEOUT
    assert "COMMAND_ACK for MAV_CMD_NAV_RETURN_TO_LAUNCH" in result.detail

    # A late ACK changes nothing.
    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))
    assert executor.take_results() == []


@pytest.mark.parametrize("command_type, custom_mode, mode_name", [
    ("RESUME_MISSION", 10, "AUTO"),
    ("LOITER", 12, "LOITER"),
])
def test_mode_change_uses_do_set_mode(executor, reader, command_type, custom_mode, mode_name):
    assert mavutil.mode_mapping_apm[custom_mode] == mode_name

    executor.handle_message(command(command_type))

    name, args = reader.sent()[0]
    assert name == "command_long"
    assert args[2] == mavlink.MAV_CMD_DO_SET_MODE
    assert args[4] == mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
    assert args[5] == custom_mode

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_DO_SET_MODE))
    result = single_result(executor)
    assert result.status is CommandStatus.ACCEPTED
    assert mode_name in result.detail


def test_goto_sends_do_reposition_as_command_int(executor, reader):
    executor.handle_message(command("GOTO", {"lat": 38.6063123, "lon": 39.2914123, "alt": 120}))

    name, args = reader.sent()[0]
    assert name == "command_int"
    (target_system, target_component, frame, cmd, current, autocontinue,
     speed, flags, radius, yaw, x, y, z) = args
    assert (target_system, target_component) == (DRONE, 1)
    assert frame == mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT
    assert cmd == mavlink.MAV_CMD_DO_REPOSITION
    assert flags == mavlink.MAV_DO_REPOSITION_FLAGS_CHANGE_MODE
    assert math.isnan(yaw)
    assert (x, y, z) == (386063123, 392914123, 120.0)

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_DO_REPOSITION))
    assert single_result(executor).status is CommandStatus.ACCEPTED


# --- Validation, expiry, unknown aircraft --------------------------------------------

def test_command_older_than_ten_seconds_is_expired_and_not_sent(executor, reader):
    executor.handle_message(command("RTL", issued_at=NOW - timedelta(seconds=10.1)))

    result = single_result(executor)
    assert result.status is CommandStatus.EXPIRED
    assert reader.sent() == []


def test_command_ten_seconds_old_still_runs(executor, reader):
    executor.handle_message(command("RTL", issued_at=NOW - timedelta(seconds=10)))
    assert executor.take_results() == []
    assert len(reader.sent()) == 1


def test_unknown_drone_is_rejected(executor):
    executor.handle_message(command("RTL", drone=9))

    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert result.drone_id == 9
    assert "unknown drone 9" in result.detail


def test_invalid_command_is_rejected(executor, reader):
    executor.handle_message(command("GOTO", {"lat": 38.6}, command_id="11111111-1111-4111-8111-111111111111"))

    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert result.command_id == "11111111-1111-4111-8111-111111111111"
    assert reader.sent() == []


def test_message_without_drone_id_produces_no_result(executor):
    executor.handle_message(b'{"schemaVersion": 1, "type": "RTL"}')
    assert executor.take_results() == []


def test_lost_link_while_sending_is_failed(executor, reader):
    executor.handle_message(command("FAULT_LOW_ALTITUDE"))
    del reader.connections[DRONE]   # the link drops after the first step was sent
    executor.on_mavlink(DRONE, param_value("ALT_HOLD_RTL", 2000))

    result = single_result(executor)
    assert result.status is CommandStatus.FAILED
    assert "link" in result.detail


# --- Ordering ------------------------------------------------------------------------

def test_commands_of_one_drone_run_one_after_the_other(executor, reader):
    executor.handle_message(command("RTL"))
    executor.handle_message(command("LOITER"))
    assert len(reader.sent()) == 1

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))
    assert [r.status for r in executor.take_results()] == [CommandStatus.ACCEPTED]
    assert reader.sent()[1][1][2] == mavlink.MAV_CMD_DO_SET_MODE


def test_different_drones_run_in_parallel(executor, reader):
    executor.handle_message(command("RTL", drone=DRONE))
    executor.handle_message(command("RTL", drone=4))
    assert len(reader.sent(DRONE)) == 1
    assert len(reader.sent(4)) == 1


def test_queued_command_starts_when_the_previous_one_times_out(executor, clock, reader):
    executor.handle_message(command("RTL"))
    executor.handle_message(command("LOITER", issued_at=NOW))
    clock.advance(4)
    executor.tick()
    clock.advance(1)
    executor.tick()          # RTL times out at 5 s, LOITER starts: 5 s old, still valid
    assert [r.status for r in executor.take_results()] == [CommandStatus.TIMEOUT]
    assert reader.sent()[-1][1][2] == mavlink.MAV_CMD_DO_SET_MODE


# --- Simulator faults ----------------------------------------------------------------

def test_battery_drain_sets_and_verifies_parameter(executor, reader):
    executor.handle_message(command("FAULT_BATTERY_DRAIN"))
    assert sent_param_sets(reader) == [("SIM_BATT_CAP_AH", 0.3)]

    # PARAM_VALUE carries a float32.
    executor.on_mavlink(DRONE, param_value("SIM_BATT_CAP_AH", 0.30000001192092896))
    assert single_result(executor).status is CommandStatus.ACCEPTED


def test_parameter_with_other_value_is_failed(executor):
    executor.handle_message(command("FAULT_BATTERY_DRAIN"))
    executor.on_mavlink(DRONE, param_value("SIM_BATT_CAP_AH", 10.0))

    result = single_result(executor)
    assert result.status is CommandStatus.FAILED
    assert "SIM_BATT_CAP_AH is 10" in result.detail


def test_param_value_of_another_parameter_is_ignored(executor):
    executor.handle_message(command("FAULT_BATTERY_DRAIN"))
    executor.on_mavlink(DRONE, param_value("ALT_HOLD_RTL", 0.3))
    assert executor.take_results() == []


def test_gps_loss_with_sim_gps1_enable(executor, reader):
    executor.handle_message(command("FAULT_GPS_LOSS", {"enabled": True}))
    requested = [args[2].decode() for name, args in reader.sent() if name == "param_request_read"]
    assert requested == ["SIM_GPS1_ENABLE", "SIM_GPS_DISABLE"]

    executor.on_mavlink(DRONE, param_value("SIM_GPS1_ENABLE", 1))
    assert sent_param_sets(reader) == [("SIM_GPS1_ENABLE", 0.0)]

    executor.on_mavlink(DRONE, param_value("SIM_GPS1_ENABLE", 0))
    assert single_result(executor).status is CommandStatus.ACCEPTED


def test_gps_loss_with_sim_gps_disable_and_cached_param(executor, reader):
    executor.handle_message(command("FAULT_GPS_LOSS", {"enabled": True}))
    executor.on_mavlink(DRONE, param_value("SIM_GPS_DISABLE", 0))
    assert sent_param_sets(reader) == [("SIM_GPS_DISABLE", 1.0)]
    executor.on_mavlink(DRONE, param_value("SIM_GPS_DISABLE", 1))
    assert single_result(executor).status is CommandStatus.ACCEPTED

    # The second time the parameter name is known: no PARAM_REQUEST_READ.
    reader.sent().clear()
    executor.handle_message(command("FAULT_GPS_LOSS", {"enabled": False}))
    assert reader.sent()[0][0] == "param_set"
    assert sent_param_sets(reader) == [("SIM_GPS_DISABLE", 0.0)]


def test_gps_parameter_not_found_is_timeout(executor, clock):
    executor.handle_message(command("FAULT_GPS_LOSS", {"enabled": True}))
    clock.advance(5)
    executor.tick()

    result = single_result(executor)
    assert result.status is CommandStatus.TIMEOUT
    assert "SIM_GPS1_ENABLE or SIM_GPS_DISABLE" in result.detail


def test_low_altitude_sets_rtl_altitude_then_rtl(executor, reader):
    executor.handle_message(command("FAULT_LOW_ALTITUDE"))
    assert sent_param_sets(reader) == [("ALT_HOLD_RTL", 2000.0)]
    assert len(reader.sent()) == 1

    executor.on_mavlink(DRONE, param_value("ALT_HOLD_RTL", 2000))
    assert reader.sent()[1][0] == "command_long"
    assert reader.sent()[1][1][2] == mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH
    assert executor.take_results() == []

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))
    assert single_result(executor).status is CommandStatus.ACCEPTED


def test_link_drop_hides_telemetry_for_the_requested_time(executor, reader, clock):
    executor.handle_message(command("FAULT_LINK_DROP", {"seconds": 5}))

    assert single_result(executor).status is CommandStatus.ACCEPTED
    assert reader.sent() == []            # nothing goes to the aircraft
    assert executor.is_link_dropped(DRONE)
    assert not executor.is_link_dropped(4)

    clock.advance(4.9)
    assert executor.is_link_dropped(DRONE)
    clock.advance(0.1)
    assert not executor.is_link_dropped(DRONE)


def test_reset_restores_everything_and_cancels_link_drop(executor, reader):
    executor.handle_message(command("FAULT_LINK_DROP", {"seconds": 60}))
    executor.take_results()

    executor.handle_message(command("FAULT_RESET"))
    assert not executor.is_link_dropped(DRONE)

    executor.on_mavlink(DRONE, param_value("SIM_BATT_CAP_AH", 10))
    executor.on_mavlink(DRONE, param_value("SIM_GPS1_ENABLE", 0))   # answer to the lookup
    executor.on_mavlink(DRONE, param_value("SIM_GPS1_ENABLE", 1))   # answer to the PARAM_SET
    executor.on_mavlink(DRONE, param_value("ALT_HOLD_RTL", 10000))

    assert sent_param_sets(reader) == [("SIM_BATT_CAP_AH", 10.0), ("SIM_GPS1_ENABLE", 1.0),
                                       ("ALT_HOLD_RTL", 10000.0)]
    result = single_result(executor)
    assert result.status is CommandStatus.ACCEPTED
    assert "link drop cancelled" in result.detail


# --- Takeoff and disarm ----------------------------------------------------------------

ARM = mavlink.MAV_CMD_COMPONENT_ARM_DISARM


def statustext(text):
    return mavlink.MAVLink_statustext_message(severity=mavlink.MAV_SEVERITY_CRITICAL, text=text.encode())


def start_takeoff_until_arm(executor, reader, issued_at=NOW):
    """TAKEOFF up to the point where it waits for the arm COMMAND_ACK."""
    executor.handle_message(command("TAKEOFF", {"alt": 60}, issued_at=issued_at))
    executor.on_mavlink(DRONE, param_value("TKOFF_ALT", 60))
    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_DO_SET_MODE))
    assert executor.take_results() == []


def test_takeoff_sets_altitude_then_mode_then_arms(executor, reader):
    executor.handle_message(command("TAKEOFF", {"alt": 80}))
    assert sent_param_sets(reader) == [("TKOFF_ALT", 80.0)]
    assert len(reader.sent()) == 1          # nothing else before PARAM_VALUE

    executor.on_mavlink(DRONE, param_value("TKOFF_ALT", 80))
    name, args = reader.sent()[1]
    assert name == "command_long"
    assert args[2] == mavlink.MAV_CMD_DO_SET_MODE
    assert args[4] == mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
    assert args[5] == 13
    assert mavutil.mode_mapping_apm[13] == "TAKEOFF"
    assert len(reader.sent()) == 2          # no arm before the mode ACK

    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_DO_SET_MODE))
    assert reader.sent()[2] == ("command_long", (DRONE, 1, ARM, 0, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    assert executor.take_results() == []

    executor.on_mavlink(DRONE, ack(ARM))
    result = single_result(executor)
    assert result.status is CommandStatus.ACCEPTED
    assert result.detail == "TKOFF_ALT=80, mode TAKEOFF set, armed"


def test_takeoff_without_alt_uses_60_m(executor, reader):
    executor.handle_message(command("TAKEOFF"))
    assert sent_param_sets(reader) == [("TKOFF_ALT", 60.0)]


def test_takeoff_with_wrong_altitude_back_is_failed_and_not_armed(executor, reader):
    executor.handle_message(command("TAKEOFF", {"alt": 60}))
    executor.on_mavlink(DRONE, param_value("TKOFF_ALT", 50))

    assert single_result(executor).status is CommandStatus.FAILED
    assert len(reader.sent()) == 1


def test_takeoff_mode_refused_is_rejected_and_not_armed(executor, reader):
    executor.handle_message(command("TAKEOFF", {"alt": 60}))
    executor.on_mavlink(DRONE, param_value("TKOFF_ALT", 60))
    executor.on_mavlink(DRONE, ack(mavlink.MAV_CMD_DO_SET_MODE, mavlink.MAV_RESULT_DENIED))

    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert "MAV_CMD_DO_SET_MODE" in result.detail
    assert not [args for name, args in reader.sent() if name == "command_long" and args[2] == ARM]


def test_refused_arm_reports_prearm_reasons(executor, reader, clock):
    clock.advance(10)
    executor.on_mavlink(DRONE, statustext("PreArm: old reason"))       # more than 3 s before the command
    clock.advance(3.5)
    executor.on_mavlink(DRONE, statustext("PreArm: GPS not healthy"))  # 2 s before the command
    executor.on_mavlink(DRONE, statustext("Some other message"))
    executor.on_mavlink(4, statustext("PreArm: of another drone"))
    clock.advance(2)

    start_takeoff_until_arm(executor, reader, issued_at=clock.utc)
    executor.on_mavlink(DRONE, ack(ARM, mavlink.MAV_RESULT_FAILED))
    # ArduPilot sends the reasons after the ACK; the result waits for them.
    assert executor.take_results() == []
    executor.on_mavlink(DRONE, statustext("PreArm: Throttle not zero"))
    executor.on_mavlink(DRONE, statustext("PreArm: GPS not healthy"))  # repeated
    clock.advance(0.9)
    executor.tick()
    assert executor.take_results() == []

    clock.advance(0.1)
    executor.tick()
    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert result.detail == ("arming refused by the aircraft: MAV_RESULT_FAILED; "
                             "PreArm: GPS not healthy; PreArm: Throttle not zero")


def test_refused_arm_without_prearm_messages_is_rejected(executor, reader, clock):
    start_takeoff_until_arm(executor, reader)
    executor.on_mavlink(DRONE, ack(ARM, mavlink.MAV_RESULT_DENIED))
    clock.advance(1)
    executor.tick()

    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert result.detail == "arming refused by the aircraft: MAV_RESULT_DENIED"


@pytest.mark.parametrize("answered", [0, 1, 2])
def test_takeoff_step_without_answer_is_timeout(executor, reader, clock, answered):
    executor.handle_message(command("TAKEOFF", {"alt": 60}))
    replies = [param_value("TKOFF_ALT", 60), ack(mavlink.MAV_CMD_DO_SET_MODE)]
    for reply in replies[:answered]:
        clock.advance(4)                     # each step has its own 5 s
        executor.on_mavlink(DRONE, reply)
    clock.advance(4.9)
    executor.tick()
    assert executor.take_results() == []

    clock.advance(0.1)
    executor.tick()
    result = single_result(executor)
    assert result.status is CommandStatus.TIMEOUT
    expected = ["PARAM_VALUE for TKOFF_ALT", "COMMAND_ACK for MAV_CMD_DO_SET_MODE",
                "COMMAND_ACK for MAV_CMD_COMPONENT_ARM_DISARM"][answered]
    assert expected in result.detail


def test_disarm_without_force(executor, reader):
    executor.handle_message(command("DISARM"))
    assert reader.sent() == [("command_long", (DRONE, 1, ARM, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))]

    executor.on_mavlink(DRONE, ack(ARM))
    result = single_result(executor)
    assert result.status is CommandStatus.ACCEPTED
    assert result.detail == "disarmed"


def test_refused_disarm_is_rejected(executor):
    executor.handle_message(command("DISARM"))
    executor.on_mavlink(DRONE, ack(ARM, mavlink.MAV_RESULT_DENIED))

    result = single_result(executor)
    assert result.status is CommandStatus.REJECTED
    assert "MAV_RESULT_DENIED" in result.detail
