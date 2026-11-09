"""Tests of the command-related parts of mavlink_reader.py: reply forwarding and connection lookup."""

from pymavlink import mavutil

from edge_adapter.mavlink_reader import MavlinkReader, _Link

mavlink = mavutil.mavlink


def from_aircraft(msg, sysid=3, compid=mavlink.MAV_COMP_ID_AUTOPILOT1):
    msg._header = mavlink.MAVLink_header(msg.id, srcSystem=sysid, srcComponent=compid)
    return msg


def make_reader():
    reader = MavlinkReader([], stream_rate_hz=10)
    link = _Link("tcp:127.0.0.1:5762")
    link.conn = object()
    link.stream_requested = True
    return reader, link


def test_command_replies_go_to_listeners_and_are_not_stored():
    reader, link = make_reader()
    received = []
    reader.add_listener(lambda sysid, msg: received.append((sysid, msg.get_type())))

    reader._handle(link, from_aircraft(mavlink.MAVLink_command_ack_message(command=20, result=0)), now=1.0)
    reader._handle(link, from_aircraft(mavlink.MAVLink_param_value_message(
        param_id=b"ALT_HOLD_RTL", param_value=2000, param_type=9, param_count=1, param_index=0)), now=1.0)

    reader._handle(link, from_aircraft(mavlink.MAVLink_statustext_message(
        severity=2, text=b"PreArm: GPS not healthy")), now=1.0)

    assert received == [(3, "COMMAND_ACK"), (3, "PARAM_VALUE"), (3, "STATUSTEXT")]
    assert 3 not in reader.fleet


def test_replies_from_other_components_are_ignored():
    reader, link = make_reader()
    received = []
    reader.add_listener(lambda sysid, msg: received.append(sysid))

    reader._handle(link, from_aircraft(mavlink.MAVLink_command_ack_message(command=20, result=0),
                                       compid=mavlink.MAV_COMP_ID_CAMERA), now=1.0)

    assert received == []


def test_failing_listener_does_not_stop_reading():
    reader, link = make_reader()
    received = []

    def broken(sysid, msg):
        raise RuntimeError("boom")

    reader.add_listener(broken)
    reader.add_listener(lambda sysid, msg: received.append(sysid))

    reader._handle(link, from_aircraft(mavlink.MAVLink_command_ack_message(command=20, result=0)), now=1.0)

    assert received == [3]


def test_connection_for_returns_the_link_the_aircraft_is_heard_on():
    reader, link = make_reader()
    assert reader.connection_for(3) is None

    reader._handle(link, from_aircraft(mavlink.MAVLink_command_ack_message(command=20, result=0)), now=1.0)
    assert reader.connection_for(3) is link.conn

    link.conn = None   # the link dropped
    assert reader.connection_for(3) is None
