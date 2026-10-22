"""
OTONOM-C3 - Second experiment: read live telemetry from a fleet of ArduPlane SITL aircraft.

What this script does:
  1. Opens one MAVLink connection per aircraft and waits for its heartbeat.
  2. Asks every autopilot to start streaming telemetry.
  3. Reads all connections in a single loop without blocking (no threads).
  4. Keeps the latest state of each aircraft, keyed by its system ID.
  5. Prints a table with one row per aircraft once per second.

How to run (inside the Ubuntu / WSL terminal, while SITL is running):
  sim_vehicle.py -v ArduPlane --count 3 --auto-sysid     (in another terminal)
  source ~/venv-ardupilot/bin/activate
  cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/experiments
  python3 read_fleet.py

Stop with Ctrl+C.
"""

import time

from pymavlink import mavutil

# --- Fleet configuration ------------------------------------------------------
# With --count N, sim_vehicle.py starts N SITL instances and shifts every port
# of instance i by i * 10. SERIAL0 (5760, 5770, ...) is taken by MAVProxy,
# so we use SERIAL1 (5762, 5772, ...). To grow the fleet, change FLEET_SIZE only.
FLEET_SIZE = 3
BASE_PORT = 5762
PORT_STEP = 10

# --auto-sysid gives instance i the system ID i + 1, so aircraft IHA-1 has
# MAV_SYSID 1, IHA-2 has 2, and so on.
EXPECTED_SYSIDS = [i + 1 for i in range(FLEET_SIZE)]
CONNECTION_STRINGS = [f"tcp:127.0.0.1:{BASE_PORT + i * PORT_STEP}" for i in range(FLEET_SIZE)]

HEARTBEAT_TIMEOUT = 5  # seconds to wait for the first heartbeat of each aircraft
STREAM_RATE_HZ = 2     # telemetry messages per second, per stream
STALE_AFTER = 3        # seconds without any message before a link counts as lost
PRINT_INTERVAL = 1     # seconds between two tables

# The MAVLink message types we care about. Everything else is ignored.
WANTED = {"HEARTBEAT", "GLOBAL_POSITION_INT", "SYS_STATUS", "GPS_RAW_INT"}


def empty_state():
    """Latest known values of one aircraft. None means "not received yet"."""
    return {
        "mode": None,
        "armed": None,
        "lat": None,
        "lon": None,
        "alt_rel": None,
        "battery": None,
        "satellites": None,
        "last_seen": None,  # time.monotonic() of the last accepted message
    }


# One state dictionary per expected aircraft, keyed by system ID.
fleet = {sysid: empty_state() for sysid in EXPECTED_SYSIDS}


# --- Connecting -----------------------------------------------------------------
def connect(connection_string):
    """Open a link and wait for a heartbeat. Returns None if it does not answer."""
    print(f"Connecting to {connection_string} ...")
    try:
        conn = mavutil.mavlink_connection(connection_string)
    except OSError as err:
        # Nothing is listening on that port (for example, SITL instance not started).
        print(f"  WARNING: cannot open {connection_string}: {err}")
        return None

    # Unlike the first experiment, we wait with a timeout. wait_heartbeat()
    # returns None if no heartbeat arrives in time, so one missing aircraft
    # does not stop the whole fleet from starting.
    if conn.wait_heartbeat(timeout=HEARTBEAT_TIMEOUT) is None:
        print(f"  WARNING: no heartbeat on {connection_string} "
              f"after {HEARTBEAT_TIMEOUT}s, skipping it")
        conn.close()
        return None
    print(f"  Connected: system {conn.target_system}, component {conn.target_component}")

    # Ask this autopilot to stream all standard telemetry at STREAM_RATE_HZ.
    conn.mav.request_data_stream_send(
        conn.target_system,
        conn.target_component,
        mavutil.mavlink.MAV_DATA_STREAM_ALL,
        STREAM_RATE_HZ,  # rate in Hz
        1,               # 1 = start streaming, 0 = stop
    )
    return conn


# --- Handling one message ----------------------------------------------------------
def handle(msg):
    """Copy the useful fields of one message into the state of its sender."""
    if msg.get_type() not in WANTED:
        return

    # A link can also carry messages from other components (camera, gimbal,
    # another ground station...). We only trust the autopilot itself.
    if msg.get_srcComponent() != mavutil.mavlink.MAV_COMP_ID_AUTOPILOT1:
        return

    # The sender's system ID tells us which aircraft this is - not the link it
    # came from. Unknown system IDs (not part of our fleet) are ignored.
    state = fleet.get(msg.get_srcSystem())
    if state is None:
        return

    kind = msg.get_type()
    if kind == "HEARTBEAT":
        state["mode"] = mavutil.mode_string_v10(msg)
        state["armed"] = bool(msg.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED)

    elif kind == "GLOBAL_POSITION_INT":
        # lat/lon are degrees * 10^7, relative_alt is millimetres above home.
        state["lat"] = msg.lat / 1e7
        state["lon"] = msg.lon / 1e7
        state["alt_rel"] = msg.relative_alt / 1000

    elif kind == "SYS_STATUS":
        # battery_remaining is a percentage, -1 means unknown.
        state["battery"] = msg.battery_remaining if msg.battery_remaining >= 0 else None

    elif kind == "GPS_RAW_INT":
        state["satellites"] = msg.satellites_visible

    state["last_seen"] = time.monotonic()


# --- Printing the table --------------------------------------------------------------
def fmt(value, pattern):
    """Format a value, or show '-' if we have not received it yet."""
    return "-" if value is None else pattern.format(value)


def print_table():
    now = time.monotonic()
    header = (f"{'IHA':<7}{'MOD':<11}{'ARMED':<7}{'ENLEM':>12}{'BOYLAM':>13}"
              f"{'IRTIFA':>9}{'BATARYA':>9}{'UYDU':>6}  {'SON MESAJ':<12}")

    # "\033[2J\033[H" clears the terminal and moves the cursor to the top,
    # so the table stays in place instead of scrolling.
    lines = ["\033[2J\033[H" + header, "-" * len(header)]

    for sysid in EXPECTED_SYSIDS:
        state = fleet[sysid]
        name = f"IHA-{sysid}"
        last = state["last_seen"]

        if last is None or now - last > STALE_AFTER:
            lines.append(f"{name:<7}BAGLANTI YOK")
            continue

        lines.append(
            f"{name:<7}"
            f"{fmt(state['mode'], '{}'):<11}"
            f"{fmt(state['armed'], '{}'):<7}"
            f"{fmt(state['lat'], '{:.6f}'):>12}"
            f"{fmt(state['lon'], '{:.6f}'):>13}"
            f"{fmt(state['alt_rel'], '{:.1f}m'):>9}"
            f"{fmt(state['battery'], '{}%'):>9}"
            f"{fmt(state['satellites'], '{}'):>6}"
            f"  {now - last:.1f}s once"
        )

    lines.append("\nCikmak icin Ctrl+C")
    print("\n".join(lines), flush=True)


# --- Main loop -------------------------------------------------------------------------
connections = [c for c in (connect(cs) for cs in CONNECTION_STRINGS) if c is not None]
if not connections:
    print("No aircraft answered. Is SITL running?")
    raise SystemExit(1)
print(f"{len(connections)}/{FLEET_SIZE} aircraft connected.")

next_print = time.monotonic()
try:
    while True:
        for conn in connections:
            # blocking=False returns None immediately when nothing is waiting,
            # so a quiet aircraft never holds up the others. We drain every
            # message that is already buffered before moving to the next link.
            while True:
                try:
                    msg = conn.recv_match(blocking=False)
                except OSError:
                    # The TCP link broke (SITL instance stopped). Its row will
                    # turn into "BAGLANTI YOK" after STALE_AFTER seconds.
                    break
                if msg is None:
                    break
                handle(msg)

        if time.monotonic() >= next_print:
            print_table()
            next_print += PRINT_INTERVAL

        # Without a short pause the loop would spin at 100% CPU doing nothing.
        # 10 ms is far shorter than the 500 ms between two telemetry messages.
        time.sleep(0.01)

except KeyboardInterrupt:
    print("\nStopped.")

finally:
    for conn in connections:
        conn.close()
