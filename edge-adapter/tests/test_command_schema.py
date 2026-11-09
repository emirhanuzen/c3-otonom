"""Tests of command.py and command_schema.py: command JSON -> Command, CommandResult -> JSON."""

import json
from datetime import datetime, timezone

import pytest

from edge_adapter.command import CommandResult, CommandStatus, CommandType
from edge_adapter.command_schema import InvalidCommand, build_result, parse_command, result_to_json

COMMAND_ID = "3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f"


def body(**overrides):
    message = {
        "schemaVersion": 1,
        "commandId": COMMAND_ID,
        "droneId": 3,
        "type": "RTL",
        "params": {},
        "issuedAt": "2026-11-05T12:00:00.000Z",
    }
    message.update(overrides)
    return json.dumps(message).encode()


def without(key):
    message = json.loads(body())
    del message[key]
    return json.dumps(message).encode()


def test_valid_command():
    command = parse_command(body())
    assert command.command_id == COMMAND_ID
    assert command.drone_id == 3
    assert command.type is CommandType.RTL
    assert command.params == {}
    assert command.issued_at == datetime(2026, 11, 5, 12, 0, tzinfo=timezone.utc)


def test_issued_at_with_offset_is_converted_to_utc():
    command = parse_command(body(issuedAt="2026-11-05T15:00:00+03:00"))
    assert command.issued_at == datetime(2026, 11, 5, 12, 0, tzinfo=timezone.utc)


def test_missing_params_means_empty():
    assert parse_command(without("params")).params == {}


def test_goto_params_are_normalized_to_floats():
    command = parse_command(body(type="GOTO", params={"lat": 38.6, "lon": 39, "alt": 120}))
    assert command.params == {"lat": 38.6, "lon": 39.0, "alt": 120.0}


def test_takeoff_alt_defaults_to_60_and_is_normalized():
    assert parse_command(body(type="TAKEOFF")).params == {"alt": 60.0}
    assert parse_command(body(type="TAKEOFF", params={"alt": 30})).params == {"alt": 30.0}
    assert parse_command(body(type="TAKEOFF", params={"alt": 300})).params == {"alt": 300.0}


def test_disarm_takes_no_params():
    command = parse_command(body(type="DISARM", params={"force": True}))
    assert command.type is CommandType.DISARM
    assert command.params == {}


SQUARE = [[38.60, 39.30], [38.60, 39.31], [38.61, 39.31], [38.61, 39.30]]


def test_fence_upload_params_are_normalized():
    command = parse_command(body(type="FENCE_UPLOAD", params={
        "version": "a1b2", "action": "RTL", "polygons": [SQUARE, [[38, 39], [38, 40], [39, 40]]]}))
    assert command.type is CommandType.FENCE_UPLOAD
    assert command.params == {
        "version": "a1b2",
        "action": "RTL",
        "polygons": [[(38.60, 39.30), (38.60, 39.31), (38.61, 39.31), (38.61, 39.30)],
                     [(38.0, 39.0), (38.0, 40.0), (39.0, 40.0)]],
    }


def test_fence_upload_closed_ring_is_opened():
    command = parse_command(body(type="FENCE_UPLOAD", params={
        "version": "v", "action": "REPORT", "polygons": [SQUARE + [SQUARE[0]]]}))
    assert len(command.params["polygons"][0]) == 4


def test_fence_upload_with_no_polygons():
    command = parse_command(body(type="FENCE_UPLOAD", params={"version": "v", "action": "RTL", "polygons": []}))
    assert command.params["polygons"] == []


def test_route_params_are_normalized():
    command = parse_command(body(type="ROUTE", params={"waypoints": [[38.61, 39.28, 120], [38.6, 39.29, 100.5]]}))
    assert command.type is CommandType.ROUTE
    assert command.params == {"waypoints": [(38.61, 39.28, 120.0), (38.6, 39.29, 100.5)]}


def test_extra_params_are_ignored():
    command = parse_command(body(type="FAULT_GPS_LOSS", params={"enabled": True, "note": "x"}))
    assert command.params == {"enabled": True}


@pytest.mark.parametrize("command_type, params", [
    ("GOTO", {"lat": 38.6, "lon": 39.2}),                      # alt missing
    ("GOTO", {"lat": 91, "lon": 39.2, "alt": 100}),
    ("GOTO", {"lat": 38.6, "lon": 181, "alt": 100}),
    ("GOTO", {"lat": 38.6, "lon": 39.2, "alt": -1}),
    ("GOTO", {"lat": "38.6", "lon": 39.2, "alt": 100}),
    ("GOTO", {"lat": True, "lon": 39.2, "alt": 100}),
    ("TAKEOFF", {"alt": 29}),
    ("TAKEOFF", {"alt": 301}),
    ("TAKEOFF", {"alt": "60"}),
    ("TAKEOFF", {"alt": None}),
    ("FAULT_GPS_LOSS", {}),
    ("FAULT_GPS_LOSS", {"enabled": 1}),
    ("FAULT_LINK_DROP", {}),
    ("FAULT_LINK_DROP", {"seconds": 0}),
    ("FAULT_LINK_DROP", {"seconds": 61}),
    ("RTL", []),                                               # params not an object
    ("ROUTE", {}),
    ("ROUTE", {"waypoints": []}),
    ("ROUTE", {"waypoints": [[38.6, 39.3]]}),                     # alt missing
    ("ROUTE", {"waypoints": [[38.6, 39.3, -1]]}),
    ("ROUTE", {"waypoints": [[91, 39.3, 100]]}),
    ("ROUTE", {"waypoints": [[38.6, 39.3, True]]}),
    ("ROUTE", {"waypoints": [[38.6, 39.3, 100]] * 101}),
    ("FENCE_UPLOAD", {"action": "RTL", "polygons": []}),       # version missing
    ("FENCE_UPLOAD", {"version": "", "action": "RTL", "polygons": []}),
    ("FENCE_UPLOAD", {"version": "v", "action": "LAND", "polygons": []}),
    ("FENCE_UPLOAD", {"version": "v", "action": "RTL"}),       # polygons missing
    ("FENCE_UPLOAD", {"version": "v", "action": "RTL", "polygons": [SQUARE[:2]]}),
    ("FENCE_UPLOAD", {"version": "v", "action": "RTL", "polygons": [[[38.6, 39.3, 0]] * 3]}),
    ("FENCE_UPLOAD", {"version": "v", "action": "RTL", "polygons": [[[91, 39.3]] + SQUARE[1:]]}),
    ("FENCE_UPLOAD", {"version": "v", "action": "RTL", "polygons": [[["38.6", 39.3]] + SQUARE[1:]]}),
    ("FENCE_UPLOAD", {"version": "v", "action": "RTL", "polygons": [SQUARE] * 126}),  # 504 vertices
])
def test_invalid_params_are_rejected_with_ids(command_type, params):
    with pytest.raises(InvalidCommand) as error:
        parse_command(body(type=command_type, params=params))
    assert error.value.command_id == COMMAND_ID
    assert error.value.drone_id == 3
    assert error.value.command_type == command_type


@pytest.mark.parametrize("seconds", [1, 30, 60, 2.5])
def test_link_drop_seconds_in_range(seconds):
    assert parse_command(body(type="FAULT_LINK_DROP", params={"seconds": seconds})).params == {"seconds": seconds}


def test_unknown_type_is_rejected():
    with pytest.raises(InvalidCommand, match="unknown command type 'SELF_DESTRUCT'") as error:
        parse_command(body(type="SELF_DESTRUCT"))
    assert error.value.drone_id == 3


@pytest.mark.parametrize("message", [
    body(schemaVersion=2),
    without("schemaVersion"),
    body(commandId="not-a-uuid"),
    without("commandId"),
    body(issuedAt="yesterday"),
    body(issuedAt="2026-11-05T12:00:00"),      # no time zone
    without("issuedAt"),
    without("type"),
])
def test_bad_envelope_is_rejected_but_answerable(message):
    with pytest.raises(InvalidCommand) as error:
        parse_command(message)
    assert error.value.drone_id == 3


@pytest.mark.parametrize("message", [
    b"{not json",
    b"\xff\xfe",
    b"[1, 2]",
    body(droneId=None),
    body(droneId=0),
    body(droneId="3"),
    body(droneId=True),
])
def test_message_without_usable_drone_id_cannot_be_answered(message):
    with pytest.raises(InvalidCommand) as error:
        parse_command(message)
    assert error.value.drone_id is None


def test_result_json():
    result = CommandResult(command_id=COMMAND_ID, drone_id=3, status=CommandStatus.TIMEOUT,
                           detail="no COMMAND_ACK within 5 s",
                           completed_at=datetime(2026, 11, 5, 12, 0, 5, 123456, tzinfo=timezone.utc))
    assert build_result(result) == {
        "schemaVersion": 1,
        "commandId": COMMAND_ID,
        "droneId": 3,
        "status": "TIMEOUT",
        "detail": "no COMMAND_ACK within 5 s",
        "completedAt": "2026-11-05T12:00:05.123Z",
    }
    assert json.loads(result_to_json(result)) == build_result(result)
