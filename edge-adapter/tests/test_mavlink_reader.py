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


def test_fence_upload_replies_go_to_listeners():
    reader, link = make_reader()
    received = []
    reader.add_listener(lambda sysid, msg: received.append(msg.get_type()))

    fence = mavlink.MAV_MISSION_TYPE_FENCE
    reader._handle(link, from_aircraft(mavlink.MAVLink_mission_request_int_message(
        target_system=255, target_component=0, seq=0, mission_type=fence)), now=1.0)
    reader._handle(link, from_aircraft(mavlink.MAVLink_mission_request_message(
        target_system=255, target_component=0, seq=1, mission_type=fence)), now=1.0)
    reader._handle(link, from_aircraft(mavlink.MAVLink_mission_ack_message(
        target_system=255, target_component=0, type=mavlink.MAV_MISSION_ACCEPTED, mission_type=fence)), now=1.0)

    assert received == ["MISSION_REQUEST_INT", "MISSION_REQUEST", "MISSION_ACK"]
    assert 3 not in reader.fleet


def test_mission_current_goes_to_listeners():
    reader, link = make_reader()
    received = []
    reader.add_listener(lambda sysid, msg: received.append(msg.get_type()))

    msg = from_aircraft(mavlink.MAVLink_mission_current_message(seq=1))
    reader._handle(link, msg, now=1.0)

    assert received == ["MISSION_CURRENT"]
    # Also stored, for the telemetry's status.missionSeq.
    assert reader.latest(3, "MISSION_CURRENT") is msg


def test_latest_gives_the_stored_message():
    reader, link = make_reader()
    assert reader.latest(3, "FENCE_STATUS") is None
    msg = from_aircraft(mavlink.MAVLink_fence_status_message(
        breach_status=0, breach_count=0, breach_type=0, breach_time=0))
    reader._handle(link, msg, now=1.0)

    assert reader.latest(3, "FENCE_STATUS") is msg
    assert reader.latest(3, "GLOBAL_POSITION_INT") is None


def test_fence_status_is_stored():
    reader, link = make_reader()
    msg = from_aircraft(mavlink.MAVLink_fence_status_message(
        breach_status=1, breach_count=2, breach_type=mavlink.FENCE_BREACH_BOUNDARY, breach_time=1))

    reader._handle(link, msg, now=1.0)

    assert reader.fleet[3].messages["FENCE_STATUS"] is msg


class RecordingMav:
    def __init__(self):
        self.sent = []

    def request_data_stream_send(self, *args):
        self.sent.append(("request_data_stream", args))

    def command_long_send(self, *args):
        self.sent.append(("command_long", args))


class RecordingConnection:
    def __init__(self):
        self.mav = RecordingMav()


def test_first_heartbeat_requests_the_stream_and_fence_status():
    reader, link = make_reader()
    link.conn = RecordingConnection()
    link.stream_requested = False
    heartbeat = from_aircraft(mavlink.MAVLink_heartbeat_message(
        type=mavlink.MAV_TYPE_FIXED_WING, autopilot=mavlink.MAV_AUTOPILOT_ARDUPILOTMEGA,
        base_mode=0, custom_mode=0, system_status=mavlink.MAV_STATE_STANDBY, mavlink_version=3))

    reader._handle(link, heartbeat, now=1.0)

    names = [name for name, _ in link.conn.mav.sent]
    assert names == ["request_data_stream", "command_long", "command_long"]
    interval = link.conn.mav.sent[1][1]
    assert interval[2] == mavlink.MAV_CMD_SET_MESSAGE_INTERVAL
    assert interval[4] == mavlink.MAVLINK_MSG_ID_FENCE_STATUS
    assert interval[5] == 1_000_000
    home = link.conn.mav.sent[2][1]
    assert (home[2], home[4], home[5]) == (mavlink.MAV_CMD_SET_MESSAGE_INTERVAL,
                                           mavlink.MAVLINK_MSG_ID_HOME_POSITION, 5_000_000)


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
