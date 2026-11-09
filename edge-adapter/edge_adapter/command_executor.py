"""
Carries out commands on the aircraft over MAVLink.

Everything runs in the main loop's thread, the only one that touches the
pymavlink connections:
  - handle_message() is called with every command taken from RabbitMQ;
  - on_mavlink() is called by MavlinkReader.poll() with every COMMAND_ACK,
    PARAM_VALUE, STATUSTEXT, MISSION_REQUEST(_INT), MISSION_ACK and MISSION_CURRENT
    an autopilot sends;
  - tick() is called once per loop to detect timeouts.

A command is sent on the same connection the aircraft is read from, and its
reply is matched by the reader's on_mavlink() call. Each command is written as
a generator: it sends a MAVLink message, yields an _Await describing the reply
it needs, and gets that reply back from send(). This keeps multi-step commands
(set a parameter, then RTL) readable without threads or callbacks.

The commands of one aircraft run one after the other, so two replies of the
same kind (two COMMAND_ACKs for RTL) can never be confused. Different aircraft
run in parallel.
"""

import logging
import math
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable

from pymavlink import mavutil

from edge_adapter.command import Command, CommandResult, CommandStatus, CommandType
from edge_adapter.command_schema import InvalidCommand, parse_command

log = logging.getLogger(__name__)
mavlink = mavutil.mavlink

REPLY_TIMEOUT_S = 5    # each awaited reply (COMMAND_ACK, PARAM_VALUE, fence item request) must arrive within this
MAX_COMMAND_AGE_S = 10  # a command issued longer ago than this is not carried out (EXPIRED)
# A refused TAKEOFF reports the "PreArm:" STATUSTEXTs received from this long before
# the command started until it ended.
PREARM_WINDOW_S = 3
# ArduPilot sends the COMMAND_ACK of a refused arm at once but queues the STATUSTEXTs
# with the reasons, so they arrive after it; they are waited for this long.
PREARM_GRACE_S = 1
PREARM_PREFIX = "PreArm:"
PREARM_MEMORY = 20      # PreArm messages kept per aircraft

AUTOPILOT = mavlink.MAV_COMP_ID_AUTOPILOT1

# ArduPlane custom_mode numbers (mavutil.mode_mapping_apm).
PLANE_MODE_AUTO = 10
PLANE_MODE_LOITER = 12
PLANE_MODE_TAKEOFF = 13

PARAM_TAKEOFF_ALT = "TKOFF_ALT"              # metres

# Onboard geofence (FENCE_UPLOAD).
PARAM_FENCE_ACTION = "FENCE_ACTION"
PARAM_FENCE_TYPE = "FENCE_TYPE"              # bitmask; FENCE_TYPE_POLYGON is the polygon fences' bit
PARAM_FENCE_ENABLE = "FENCE_ENABLE"
FENCE_TYPE_POLYGON = 4
FENCE_ACTION_VALUES = {"REPORT": 0, "RTL": 1}  # ArduPlane FENCE_ACTION
MISSION_TYPE_FENCE = mavlink.MAV_MISSION_TYPE_FENCE
MISSION_TYPE_MISSION = mavlink.MAV_MISSION_TYPE_MISSION

# SITL parameters used by the fault commands.
PARAM_BATTERY_CAPACITY = "SIM_BATT_CAP_AH"   # amp-hours
PARAM_RTL_ALTITUDE = "ALT_HOLD_RTL"          # centimetres
# ArduPilot 4.6 replaced SIM_GPS_DISABLE (1 = off) with SIM_GPS1_ENABLE (1 = on);
# a firmware has one or the other, so the adapter asks which one exists.
PARAM_GPS_ENABLE = "SIM_GPS1_ENABLE"
PARAM_GPS_DISABLE = "SIM_GPS_DISABLE"

BATTERY_DRAIN_CAPACITY_AH = 0.3
BATTERY_NORMAL_CAPACITY_AH = 10
LOW_RTL_ALTITUDE_CM = 2000
NORMAL_RTL_ALTITUDE_CM = 10000


@dataclass(frozen=True)
class _Await:
    """A reply a command is waiting for."""
    description: str                  # used in the TIMEOUT detail, e.g. "COMMAND_ACK for MAV_CMD_..."
    matches: Callable[[object], bool]
    timeout_s: float | None = None    # None: the executor's reply_timeout_s
    optional: bool = False            # True: at the deadline the command resumes with None instead of TIMEOUT


class _Outcome(Exception):
    """Raised inside a command generator to end the command with a status other than ACCEPTED."""

    def __init__(self, status, detail):
        super().__init__(detail)
        self.status = status
        self.detail = detail


@dataclass
class _Run:
    command: Command
    steps: object               # the command's generator
    wait: _Await | None = None
    deadline: float = 0.0       # monotonic time by which `wait` must be satisfied


def _utcnow():
    return datetime.now(timezone.utc)


class CommandExecutor:
    def __init__(self, reader, reply_timeout_s=REPLY_TIMEOUT_S, max_age_s=MAX_COMMAND_AGE_S,
                 monotonic=time.monotonic, utcnow=_utcnow):
        self.reader = reader
        self.reply_timeout_s = reply_timeout_s
        self.max_age = timedelta(seconds=max_age_s)
        self._monotonic = monotonic
        self._utcnow = utcnow
        self._queues = defaultdict(deque)  # drone_id -> commands waiting for their turn
        self._running = {}                 # drone_id -> _Run
        self._results = []                 # finished, not yet taken by the main loop
        self._gps_param = {}               # drone_id -> PARAM_GPS_ENABLE or PARAM_GPS_DISABLE
        self._link_drop_until = {}         # drone_id -> monotonic time the telemetry drop ends
        self._prearm = defaultdict(lambda: deque(maxlen=PREARM_MEMORY))  # drone_id -> (monotonic, text)

    # --- Called from the main loop ----------------------------------------------
    def handle_message(self, body):
        """Parse one command message and queue it, or answer it right away when it is invalid."""
        try:
            command = parse_command(body)
        except InvalidCommand as err:
            if err.drone_id is None:
                # No routing key for a result; the sender cannot be told.
                log.warning("Command %s drone=? type=%s status=REJECTED (not answered): %s",
                            err.command_id or "?", err.command_type or "?", err.detail)
                return
            self._finish(err.command_id, err.drone_id, err.command_type, CommandStatus.REJECTED, err.detail)
            return
        self._queues[command.drone_id].append(command)
        self._start_next(command.drone_id)

    def on_mavlink(self, drone_id, msg):
        """A COMMAND_ACK, PARAM_VALUE or STATUSTEXT from the autopilot of drone_id."""
        if msg.get_type() == "STATUSTEXT":
            text = _statustext(msg)
            if text.startswith(PREARM_PREFIX):
                self._prearm[drone_id].append((self._monotonic(), text))
        run = self._running.get(drone_id)
        if run is None or run.wait is None or not run.wait.matches(msg):
            return
        self._advance(run, msg)
        self._start_next(drone_id)

    def tick(self):
        """End commands whose reply is overdue, start waiting ones, forget finished link drops."""
        now = self._monotonic()
        for drone_id, run in list(self._running.items()):
            if run.wait is not None and now >= run.deadline:
                if run.wait.optional:
                    self._advance(run, None)
                else:
                    self._finish_run(run, CommandStatus.TIMEOUT,
                                     f"no {run.wait.description} within {self.reply_timeout_s:g} s")
        for drone_id in list(self._queues):
            self._start_next(drone_id)
        for drone_id, until in list(self._link_drop_until.items()):
            if now >= until:
                del self._link_drop_until[drone_id]

    def take_results(self):
        """Finished results, oldest first; each is returned only once."""
        results, self._results = self._results, []
        return results

    def is_link_dropped(self, drone_id):
        """True while a FAULT_LINK_DROP of this aircraft is in effect: its telemetry must not be published."""
        until = self._link_drop_until.get(drone_id)
        return until is not None and self._monotonic() < until

    # --- Running commands -------------------------------------------------------
    def _start_next(self, drone_id):
        queue = self._queues.get(drone_id)
        # A command may finish without waiting (expired, rejected, local), so keep going.
        while queue and drone_id not in self._running:
            self._start(queue.popleft())
        if queue is not None and not queue:
            del self._queues[drone_id]

    def _start(self, command):
        age = self._utcnow() - command.issued_at
        if age > self.max_age:
            self._finish_command(command, CommandStatus.EXPIRED,
                                 f"issued {age.total_seconds():.1f} s ago, the limit is "
                                 f"{self.max_age.total_seconds():g} s")
            return
        if self.reader.connection_for(command.drone_id) is None:
            self._finish_command(command, CommandStatus.REJECTED,
                                 f"unknown drone {command.drone_id} (no MAVLink link)")
            return
        run = _Run(command, self._steps(command))
        self._running[command.drone_id] = run
        self._advance(run, None)

    def _advance(self, run, reply):
        """Resume the command's generator with the reply it was waiting for."""
        try:
            run.wait = run.steps.send(reply)
        except StopIteration as done:
            self._finish_run(run, CommandStatus.ACCEPTED, done.value or "done")
            return
        except _Outcome as outcome:
            self._finish_run(run, outcome.status, outcome.detail)
            return
        except OSError as err:
            self._finish_run(run, CommandStatus.FAILED, f"sending to the aircraft failed: {err}")
            return
        timeout = run.wait.timeout_s if run.wait.timeout_s is not None else self.reply_timeout_s
        run.deadline = self._monotonic() + timeout

    def _finish_run(self, run, status, detail):
        del self._running[run.command.drone_id]
        run.steps.close()
        self._finish_command(run.command, status, detail)

    def _finish_command(self, command, status, detail):
        self._finish(command.command_id, command.drone_id, command.type.value, status, detail)

    def _finish(self, command_id, drone_id, command_type, status, detail):
        self._results.append(CommandResult(command_id=command_id, drone_id=drone_id, status=status,
                                           detail=detail, completed_at=self._utcnow()))
        log.info("Command %s drone=%s type=%s status=%s: %s",
                 command_id or "?", drone_id, command_type or "?", status.value, detail)

    # --- Command steps (generators) ----------------------------------------------
    def _steps(self, command):
        drone_id = command.drone_id
        params = command.params
        kind = command.type

        if kind is CommandType.RTL:
            return (yield from self._command_long(drone_id, mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH))
        if kind is CommandType.RESUME_MISSION:
            return (yield from self._set_mode(drone_id, "AUTO", PLANE_MODE_AUTO))
        if kind is CommandType.LOITER:
            return (yield from self._set_mode(drone_id, "LOITER", PLANE_MODE_LOITER))
        if kind is CommandType.GOTO:
            return (yield from self._reposition(drone_id, params["lat"], params["lon"], params["alt"]))
        if kind is CommandType.ROUTE:
            return (yield from self._route(drone_id, params["waypoints"]))
        if kind is CommandType.TAKEOFF:
            return (yield from self._takeoff(drone_id, params["alt"]))
        if kind is CommandType.FENCE_UPLOAD:
            return (yield from self._fence_upload(drone_id, params["version"], params["action"], params["polygons"]))
        if kind is CommandType.DISARM:
            # param1 0 = disarm; param2 stays 0 (no force), so the autopilot refuses it in flight.
            yield from self._command_long(drone_id, mavlink.MAV_CMD_COMPONENT_ARM_DISARM, 0)
            return "disarmed"
        if kind is CommandType.FAULT_BATTERY_DRAIN:
            return (yield from self._set_param(drone_id, PARAM_BATTERY_CAPACITY, BATTERY_DRAIN_CAPACITY_AH))
        if kind is CommandType.FAULT_GPS_LOSS:
            return (yield from self._set_gps(drone_id, gps_on=not params["enabled"]))
        if kind is CommandType.FAULT_LOW_ALTITUDE:
            altitude = yield from self._set_param(drone_id, PARAM_RTL_ALTITUDE, LOW_RTL_ALTITUDE_CM)
            rtl = yield from self._command_long(drone_id, mavlink.MAV_CMD_NAV_RETURN_TO_LAUNCH)
            return f"{altitude}, {rtl}"
        if kind is CommandType.FAULT_LINK_DROP:
            # Nothing is sent to the aircraft; only the adapter stops publishing it.
            self._link_drop_until[drone_id] = self._monotonic() + params["seconds"]
            return f"telemetry of drone {drone_id} is not published for {params['seconds']:g} s"
        if kind is CommandType.FAULT_RESET:
            self._link_drop_until.pop(drone_id, None)
            battery = yield from self._set_param(drone_id, PARAM_BATTERY_CAPACITY, BATTERY_NORMAL_CAPACITY_AH)
            gps = yield from self._set_gps(drone_id, gps_on=True)
            altitude = yield from self._set_param(drone_id, PARAM_RTL_ALTITUDE, NORMAL_RTL_ALTITUDE_CM)
            return f"faults reset: {battery}, {gps}, {altitude}, link drop cancelled"
        raise _Outcome(CommandStatus.REJECTED, f"unsupported command type {kind.value}")

    def _connection(self, drone_id):
        conn = self.reader.connection_for(drone_id)
        if conn is None:
            raise _Outcome(CommandStatus.FAILED, f"MAVLink link to drone {drone_id} lost")
        return conn

    def _command_long(self, drone_id, command, *params):
        """Send a COMMAND_LONG and wait for its COMMAND_ACK; a refused one ends the command REJECTED."""
        ack = yield from self._command_long_ack(drone_id, command, *params)
        return self._check_ack(command, ack)

    def _command_long_ack(self, drone_id, command, *params):
        """Send a COMMAND_LONG and return its final COMMAND_ACK, whatever its result."""
        p = list(params) + [0.0] * (7 - len(params))
        self._connection(drone_id).mav.command_long_send(drone_id, AUTOPILOT, command, 0, *p)
        return (yield self._await_ack(command))

    def _takeoff(self, drone_id, alt):
        """TKOFF_ALT, then mode TAKEOFF, then arm: ArduPlane takes off as soon as it is armed."""
        prearm_since = self._monotonic() - PREARM_WINDOW_S
        altitude = yield from self._set_param(drone_id, PARAM_TAKEOFF_ALT, alt)
        yield from self._set_mode(drone_id, "TAKEOFF", PLANE_MODE_TAKEOFF)
        command = mavlink.MAV_CMD_COMPONENT_ARM_DISARM
        ack = yield from self._command_long_ack(drone_id, command, 1)
        if ack.result != mavlink.MAV_RESULT_ACCEPTED:
            # Let the queued "PreArm:" STATUSTEXTs arrive; this wait never times out the command.
            yield _Await("PreArm STATUSTEXT", lambda m: False, timeout_s=PREARM_GRACE_S, optional=True)
            detail = f"arming refused by the aircraft: {_result_name(ack.result)}"
            reasons = self._prearm_reasons(drone_id, prearm_since)
            if reasons:
                detail += "; " + "; ".join(reasons)
            raise _Outcome(CommandStatus.REJECTED, detail)
        return f"{altitude}, mode TAKEOFF set, armed"

    def _fence_upload(self, drone_id, version, action, polygons):
        """Replace the fence items, then FENCE_ACTION, the polygon bit of FENCE_TYPE and FENCE_ENABLE=1.

        Each vertex is one MAV_CMD_NAV_FENCE_POLYGON_VERTEX_EXCLUSION item whose param1 is
        the vertex count of its polygon. The autopilot asks for the items one by one
        (MISSION_REQUEST_INT, or MISSION_REQUEST from older firmware) and ends the upload
        with a MISSION_ACK; every request must come within the reply timeout.

        An empty polygon list clears the fence items with MISSION_CLEAR_ALL and turns the
        fence off (FENCE_ENABLE=0): an enabled polygon fence without polygons could keep
        the autopilot from arming. FENCE_ACTION and FENCE_TYPE are then left as they are.
        """
        items = [(lat, lon, len(polygon)) for polygon in polygons for lat, lon in polygon]
        if not items:
            self._connection(drone_id).mav.mission_clear_all_send(drone_id, AUTOPILOT, MISSION_TYPE_FENCE)
            ack = yield _Await("MISSION_ACK for the fence clear",
                               lambda m: _is_mission(m, MISSION_TYPE_FENCE, "MISSION_ACK"))
            _check_mission_ack(ack, "fence")
            disable = yield from self._set_param(drone_id, PARAM_FENCE_ENABLE, 0)
            return f"fence {version}: fence items cleared, {disable}"
        def send_vertex(conn, seq):
            lat, lon, vertices = items[seq]
            conn.mav.mission_item_int_send(
                drone_id, AUTOPILOT, seq,
                mavlink.MAV_FRAME_GLOBAL,
                mavlink.MAV_CMD_NAV_FENCE_POLYGON_VERTEX_EXCLUSION,
                0, 0,                       # current, autocontinue: unused for fence items
                vertices, 0, 0, 0,          # param1: vertex count of this item's polygon
                round(lat * 1e7), round(lon * 1e7), 0,
                MISSION_TYPE_FENCE,
            )

        yield from self._upload_items(drone_id, MISSION_TYPE_FENCE, "fence", len(items), send_vertex)
        upload = f"{len(polygons)} polygon(s) with {len(items)} vertices uploaded"
        fence_action = yield from self._set_param(drone_id, PARAM_FENCE_ACTION, FENCE_ACTION_VALUES[action])
        fence_type = yield from self._add_polygon_fence_type(drone_id)
        enable = yield from self._set_param(drone_id, PARAM_FENCE_ENABLE, 1)
        return f"fence {version}: {upload}, {fence_action}, {fence_type}, {enable}"

    def _upload_items(self, drone_id, mission_type, label, count, send_item):
        """The upload half of the mission protocol, for any mission_type.

        MISSION_COUNT, then send_item(connection, seq) for every MISSION_REQUEST(_INT) of that
        mission_type, until the MISSION_ACK. Each request must come within the reply timeout.
        """
        self._connection(drone_id).mav.mission_count_send(drone_id, AUTOPILOT, count, mission_type)
        sent = set()
        while True:
            reply = yield _Await(f"{label} item request or MISSION_ACK ({len(sent)} of {count} items sent)",
                                 lambda m: _is_mission(m, mission_type,
                                                       "MISSION_REQUEST_INT", "MISSION_REQUEST", "MISSION_ACK"))
            if reply.get_type() == "MISSION_ACK":
                _check_mission_ack(reply, label)
                if len(sent) < count:
                    raise _Outcome(CommandStatus.FAILED,
                                   f"the aircraft ended the {label} upload after {len(sent)} of {count} items")
                return
            seq = reply.seq
            if not 0 <= seq < count:
                raise _Outcome(CommandStatus.FAILED, f"the aircraft asked for {label} item {seq} of {count}")
            # A repeated request (the item got lost on the way) is simply answered again.
            send_item(self._connection(drone_id), seq)
            sent.add(seq)

    def _route(self, drone_id, waypoints):
        """Upload the route as the mission, make item 1 current, then switch to AUTO.

        Item 0 is ArduPilot's home slot; it gets the aircraft's current position as a
        placeholder (the autopilot keeps its own home). Items 1..n-1 are NAV_WAYPOINTs and the
        target is a NAV_LOITER_UNLIM, so the plane circles there instead of ending the mission.
        The upload replaces the mission that was on the aircraft.
        """
        home = self._home_placeholder(drone_id, waypoints[0])
        items = [home] + [(mavlink.MAV_CMD_NAV_WAYPOINT, mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT, lat, lon, alt)
                          for lat, lon, alt in waypoints[:-1]]
        lat, lon, alt = waypoints[-1]
        items.append((mavlink.MAV_CMD_NAV_LOITER_UNLIM, mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT, lat, lon, alt))

        def send_item(conn, seq):
            command, frame, item_lat, item_lon, item_alt = items[seq]
            conn.mav.mission_item_int_send(
                drone_id, AUTOPILOT, seq, frame, command,
                0, 1,                       # current: no; autocontinue: yes
                0, 0, 0, 0,                 # hold, acceptance radius, pass radius / loiter radius, yaw: defaults
                round(item_lat * 1e7), round(item_lon * 1e7), item_alt,
                MISSION_TYPE_MISSION,
            )

        yield from self._upload_items(drone_id, MISSION_TYPE_MISSION, "mission", len(items), send_item)
        self._connection(drone_id).mav.mission_set_current_send(drone_id, AUTOPILOT, 1)
        yield _Await("MISSION_CURRENT with item 1", lambda m: m.get_type() == "MISSION_CURRENT" and m.seq == 1)
        mode = yield from self._set_mode(drone_id, "AUTO", PLANE_MODE_AUTO)
        return f"route of {len(waypoints)} waypoint(s) uploaded as the mission, item 1 current, {mode}"

    def _home_placeholder(self, drone_id, first_waypoint):
        """Item 0: the current position (absolute altitude), or the first waypoint when it is unknown."""
        position = self.reader.latest(drone_id, "GLOBAL_POSITION_INT")
        if position is not None:
            return (mavlink.MAV_CMD_NAV_WAYPOINT, mavlink.MAV_FRAME_GLOBAL,
                    position.lat / 1e7, position.lon / 1e7, position.alt / 1000)
        lat, lon, _ = first_waypoint
        return (mavlink.MAV_CMD_NAV_WAYPOINT, mavlink.MAV_FRAME_GLOBAL, lat, lon, 0)

    def _add_polygon_fence_type(self, drone_id):
        """Read FENCE_TYPE and set its polygon bit, keeping the other bits (max altitude, circle...)."""
        self._connection(drone_id).mav.param_request_read_send(
            drone_id, AUTOPILOT, PARAM_FENCE_TYPE.encode("ascii"), -1)
        reply = yield _Await(f"PARAM_VALUE for {PARAM_FENCE_TYPE}",
                             lambda m: m.get_type() == "PARAM_VALUE" and _param_id(m) == PARAM_FENCE_TYPE)
        current = int(round(reply.param_value))
        wanted = current | FENCE_TYPE_POLYGON
        if wanted == current:
            return f"{PARAM_FENCE_TYPE}={current}"
        return (yield from self._set_param(drone_id, PARAM_FENCE_TYPE, wanted))

    def _prearm_reasons(self, drone_id, since):
        """Distinct PreArm texts received at or after monotonic time `since`, oldest first."""
        reasons = []
        for at, text in self._prearm.get(drone_id, ()):
            if at >= since and text not in reasons:
                reasons.append(text)
        return reasons

    def _set_mode(self, drone_id, name, custom_mode):
        yield from self._command_long(drone_id, mavlink.MAV_CMD_DO_SET_MODE,
                                      mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED, custom_mode)
        return f"mode {name} set"

    def _reposition(self, drone_id, lat, lon, alt):
        """MAV_CMD_DO_REPOSITION as COMMAND_INT; lat/lon go into x/y as degrees * 10^7."""
        command = mavlink.MAV_CMD_DO_REPOSITION
        self._connection(drone_id).mav.command_int_send(
            drone_id, AUTOPILOT,
            mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT,
            command,
            0, 0,                                           # current, autocontinue: unused
            -1,                                             # param1: ground speed, -1 = default
            mavlink.MAV_DO_REPOSITION_FLAGS_CHANGE_MODE,    # param2: switch to GUIDED
            0,                                              # param3: loiter radius, 0 = default
            math.nan,                                       # param4: yaw, NaN = unchanged
            round(lat * 1e7), round(lon * 1e7), alt,
        )
        ack = yield self._await_ack(command)
        self._check_ack(command, ack)
        return f"repositioning to {lat:.7f}, {lon:.7f} at {alt:g} m"

    def _set_param(self, drone_id, name, value):
        """PARAM_SET, then check the PARAM_VALUE the autopilot answers with."""
        self._connection(drone_id).mav.param_set_send(
            drone_id, AUTOPILOT, name.encode("ascii"), float(value), mavlink.MAV_PARAM_TYPE_REAL32)
        reply = yield _Await(f"PARAM_VALUE for {name}",
                             lambda m: m.get_type() == "PARAM_VALUE" and _param_id(m) == name)
        # PARAM_VALUE carries a float32, so 0.3 comes back as 0.30000001.
        if not math.isclose(reply.param_value, value, rel_tol=1e-4, abs_tol=1e-4):
            raise _Outcome(CommandStatus.FAILED, f"{name} is {reply.param_value:g}, expected {value:g}")
        return f"{name}={value:g}"

    def _set_gps(self, drone_id, gps_on):
        name = self._gps_param.get(drone_id)
        if name is None:
            name = yield from self._find_gps_param(drone_id)
        if name == PARAM_GPS_ENABLE:
            value = 1 if gps_on else 0
        else:
            value = 0 if gps_on else 1
        return (yield from self._set_param(drone_id, name, value))

    def _find_gps_param(self, drone_id):
        """Ask for both GPS switch parameters; the one that answers exists. Cached per aircraft."""
        conn = self._connection(drone_id)
        for name in (PARAM_GPS_ENABLE, PARAM_GPS_DISABLE):
            # param_index -1: look the parameter up by name.
            conn.mav.param_request_read_send(drone_id, AUTOPILOT, name.encode("ascii"), -1)
        names = (PARAM_GPS_ENABLE, PARAM_GPS_DISABLE)
        reply = yield _Await(f"PARAM_VALUE for {PARAM_GPS_ENABLE} or {PARAM_GPS_DISABLE}",
                             lambda m: m.get_type() == "PARAM_VALUE" and _param_id(m) in names)
        name = _param_id(reply)
        self._gps_param[drone_id] = name
        return name

    @staticmethod
    def _await_ack(command):
        # IN_PROGRESS is not the final answer; keep waiting for the next ACK.
        return _Await(f"COMMAND_ACK for {_command_name(command)}",
                      lambda m: (m.get_type() == "COMMAND_ACK" and m.command == command
                                 and m.result != mavlink.MAV_RESULT_IN_PROGRESS))

    @staticmethod
    def _check_ack(command, ack):
        if ack.result != mavlink.MAV_RESULT_ACCEPTED:
            raise _Outcome(CommandStatus.REJECTED,
                           f"{_command_name(command)} refused by the aircraft: {_result_name(ack.result)}")
        return f"{_command_name(command)} accepted"


def _is_mission(msg, mission_type, *types):
    """True for a message of one of these types that belongs to the protocol of this mission_type."""
    return msg.get_type() in types and getattr(msg, "mission_type", MISSION_TYPE_MISSION) == mission_type


def _check_mission_ack(ack, label):
    if ack.type != mavlink.MAV_MISSION_ACCEPTED:
        raise _Outcome(CommandStatus.REJECTED,
                       f"{label} upload refused by the aircraft: {_enum_name('MAV_MISSION_RESULT', ack.type)}")


def _enum_name(enum, value):
    entry = mavlink.enums[enum].get(value)
    return entry.name if entry is not None else str(value)


def _param_id(msg):
    """PARAM_VALUE.param_id as a plain string; pymavlink may give bytes padded with NULs."""
    value = msg.param_id
    if isinstance(value, bytes):
        value = value.decode("ascii", errors="replace")
    return value.rstrip("\x00")


def _statustext(msg):
    """STATUSTEXT.text as a plain string; it may be bytes padded with NULs."""
    value = msg.text
    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="replace")
    return value.rstrip("\x00").strip()


def _command_name(command):
    entry = mavlink.enums["MAV_CMD"].get(command)
    return entry.name if entry is not None else str(command)


def _result_name(result):
    entry = mavlink.enums["MAV_RESULT"].get(result)
    return entry.name if entry is not None else str(result)
