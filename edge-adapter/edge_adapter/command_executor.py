"""
Carries out commands on the aircraft over MAVLink.

Everything runs in the main loop's thread, the only one that touches the
pymavlink connections:
  - handle_message() is called with every command taken from RabbitMQ;
  - on_mavlink() is called by MavlinkReader.poll() with every COMMAND_ACK and
    PARAM_VALUE an autopilot sends;
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

REPLY_TIMEOUT_S = 5    # each awaited reply (COMMAND_ACK, PARAM_VALUE) must arrive within this
MAX_COMMAND_AGE_S = 10  # a command issued longer ago than this is not carried out (EXPIRED)

AUTOPILOT = mavlink.MAV_COMP_ID_AUTOPILOT1

# ArduPlane custom_mode numbers (mavutil.mode_mapping_apm).
PLANE_MODE_AUTO = 10
PLANE_MODE_LOITER = 12

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
        """A COMMAND_ACK or PARAM_VALUE from the autopilot of drone_id."""
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
        run.deadline = self._monotonic() + self.reply_timeout_s

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
        """Send a COMMAND_LONG and wait for its COMMAND_ACK."""
        p = list(params) + [0.0] * (7 - len(params))
        self._connection(drone_id).mav.command_long_send(drone_id, AUTOPILOT, command, 0, *p)
        ack = yield self._await_ack(command)
        return self._check_ack(command, ack)

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


def _param_id(msg):
    """PARAM_VALUE.param_id as a plain string; pymavlink may give bytes padded with NULs."""
    value = msg.param_id
    if isinstance(value, bytes):
        value = value.decode("ascii", errors="replace")
    return value.rstrip("\x00")


def _command_name(command):
    entry = mavlink.enums["MAV_CMD"].get(command)
    return entry.name if entry is not None else str(command)


def _result_name(result):
    entry = mavlink.enums["MAV_RESULT"].get(result)
    return entry.name if entry is not None else str(result)
