"""
Reads MAVLink telemetry from every aircraft of the SITL fleet.

Based on experiments/read_fleet.py, with two differences:
  - Connecting never waits for a heartbeat. A link is opened and then polled
    like the others, so one missing aircraft never holds up the rest.
  - A link that cannot be opened, or that goes quiet, is closed and retried
    every RECONNECT_INTERVAL_S seconds, independently of the other links.

The reader stores the latest raw message of each wanted type per aircraft.
Turning them into JSON (and converting units) is the job of schema.py.
"""

import logging
import time
from dataclasses import dataclass, field

from pymavlink import mavutil

log = logging.getLogger(__name__)

# The MAVLink message types we care about. Everything else is ignored.
WANTED = {"HEARTBEAT", "GLOBAL_POSITION_INT", "VFR_HUD", "SYS_STATUS", "GPS_RAW_INT"}

RECONNECT_INTERVAL_S = 5  # seconds between two connection attempts of one aircraft
# pymavlink does not raise when SITL closes the TCP socket, recv() just keeps
# returning nothing. So a link that delivers no message for this long counts
# as lost. The autopilot sends a heartbeat every second, so 5 s is safe.
LINK_TIMEOUT_S = 5


@dataclass
class DroneState:
    """Latest known messages of one aircraft, keyed by message type."""
    messages: dict = field(default_factory=dict)
    last_seen: float | None = None  # time.monotonic() of the last accepted message


class _Link:
    """One TCP connection to one SITL instance."""

    def __init__(self, connection_string):
        self.connection_string = connection_string
        self.conn = None
        self.next_attempt = 0.0      # monotonic time of the next connection attempt
        self.last_rx = 0.0           # monotonic time of the last received message
        self.stream_requested = False
        self.ever_failed = False     # only the first failure in a row is logged as a warning


class MavlinkReader:
    def __init__(self, connection_strings, stream_rate_hz):
        self.links = [_Link(cs) for cs in connection_strings]
        self.stream_rate_hz = max(1, round(stream_rate_hz))
        # One state per aircraft, keyed by system ID. Filled as messages arrive.
        self.fleet = {}

    # --- Connecting -----------------------------------------------------------
    def _try_connect(self, link, now):
        try:
            # retries=0: pymavlink would otherwise retry a refused TCP connect
            # 3 times with 1 s sleeps, freezing the whole loop for every
            # missing aircraft. Connecting to 127.0.0.1 fails immediately.
            link.conn = mavutil.mavlink_connection(link.connection_string, retries=0)
        except OSError as err:
            # Nothing is listening on that port (for example, SITL instance not started).
            level = logging.DEBUG if link.ever_failed else logging.WARNING
            log.log(level, "Cannot open %s: %s (retrying every %ss)",
                    link.connection_string, err, RECONNECT_INTERVAL_S)
            link.ever_failed = True
            link.next_attempt = now + RECONNECT_INTERVAL_S
            return

        log.info("Link opened: %s", link.connection_string)
        link.last_rx = now
        link.stream_requested = False
        link.ever_failed = False

    def _drop(self, link, now, reason):
        log.warning("Link lost: %s (%s), retrying in %ss",
                    link.connection_string, reason, RECONNECT_INTERVAL_S)
        try:
            link.conn.close()
        except OSError:
            pass
        link.conn = None
        link.ever_failed = True
        link.next_attempt = now + RECONNECT_INTERVAL_S

    def _request_stream(self, link, msg):
        """Ask this autopilot to stream all standard telemetry at stream_rate_hz."""
        link.conn.mav.request_data_stream_send(
            msg.get_srcSystem(),
            msg.get_srcComponent(),
            mavutil.mavlink.MAV_DATA_STREAM_ALL,
            self.stream_rate_hz,  # rate in Hz
            1,                    # 1 = start streaming, 0 = stop
        )
        link.stream_requested = True
        log.info("Aircraft %d answered on %s, telemetry stream requested at %d Hz",
                 msg.get_srcSystem(), link.connection_string, self.stream_rate_hz)

    # --- Polling --------------------------------------------------------------
    def poll(self):
        """Read every message that is already waiting on every link. Never blocks."""
        now = time.monotonic()
        for link in self.links:
            if link.conn is None:
                if now >= link.next_attempt:
                    self._try_connect(link, now)
                continue

            try:
                # blocking=False returns None immediately when nothing is waiting,
                # so a quiet aircraft never holds up the others. We drain every
                # message that is already buffered before moving to the next link.
                while True:
                    msg = link.conn.recv_match(blocking=False)
                    if msg is None:
                        break
                    link.last_rx = now
                    self._handle(link, msg, now)
            except OSError as err:
                self._drop(link, now, err)
                continue

            if now - link.last_rx > LINK_TIMEOUT_S:
                self._drop(link, now, f"no message for {LINK_TIMEOUT_S}s")

    def _handle(self, link, msg, now):
        """Store one message in the state of its sender."""
        kind = msg.get_type()
        if kind not in WANTED:
            return

        # A link can also carry messages from other components (camera, gimbal,
        # another ground station...). We only trust the autopilot itself.
        if msg.get_srcComponent() != mavutil.mavlink.MAV_COMP_ID_AUTOPILOT1:
            return

        # The first autopilot heartbeat tells us who is on the other end of the
        # link, so this is the moment to ask for the telemetry stream.
        if kind == "HEARTBEAT" and not link.stream_requested:
            self._request_stream(link, msg)

        # The sender's system ID tells us which aircraft this is - not the link
        # it came from.
        state = self.fleet.setdefault(msg.get_srcSystem(), DroneState())
        state.messages[kind] = msg
        state.last_seen = now

    def fresh_drones(self, stale_after_s):
        """(system ID, state) of every aircraft heard from in the last stale_after_s seconds."""
        now = time.monotonic()
        return [(sysid, state) for sysid, state in sorted(self.fleet.items())
                if state.last_seen is not None and now - state.last_seen <= stale_after_s]

    def close(self):
        for link in self.links:
            if link.conn is not None:
                try:
                    link.conn.close()
                except OSError:
                    pass
                link.conn = None
