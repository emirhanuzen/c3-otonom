"""Demo: every UAV in the SITL fleet flies a plus-shaped (+) outline.

Connects to each SITL instance on its free SERIAL2 port (5763 + 10*i), so
MAVProxy (SERIAL0) and the edge adapter (SERIAL1) are left untouched.
For each vehicle: upload the mission, WP_RADIUS=30, mode AUTO, arm.

Usage: python arti_ciz.py [fleet_size]
"""

import math
import sys
import time

from pymavlink import mavutil

# SITL spawn point (must match --custom-location in ihalari-baslat.bat)
SPAWN_LAT = 38.6069
SPAWN_LON = 39.2914
SPAWN_SPACING_M = 30      # --auto-offset-line 90,30 -> 30 m east per vehicle

# Plus geometry (metres)
HALF_BAR = 100            # half of the bar width  -> bars are 200 m wide
ARM_TIP = 300             # centre to arm tip      -> shape is 600 x 600 m
CENTER_NORTH = 600        # plus centre is 600 m north of the spawn point
CENTER_SPACING = 800      # pluses side by side, 800 m apart (no overlap)

TAKEOFF_ALT = 50
BASE_ALT = 100            # each vehicle flies 20 m higher than the previous
ALT_STEP = 20

FRAME_REL = mavutil.mavlink.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT
FRAME_GLOBAL = mavutil.mavlink.MAV_FRAME_GLOBAL_INT
CMD = mavutil.mavlink


def offset(lat, lon, north_m, east_m):
    dlat = north_m / 111_320
    dlon = east_m / (111_320 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def plus_outline(a, l):
    """12 corners of a + outline, clockwise from the top-left of the top arm."""
    return [(-a, l), (a, l), (a, a), (l, a), (l, -a), (a, -a),
            (a, -l), (-a, -l), (-a, -a), (-l, -a), (-l, a), (-a, a)]


def build_mission(index, fleet_size):
    home_lat, home_lon = offset(SPAWN_LAT, SPAWN_LON, 0, index * SPAWN_SPACING_M)
    center_east = (index - (fleet_size - 1) / 2) * CENTER_SPACING
    c_lat, c_lon = offset(SPAWN_LAT, SPAWN_LON, CENTER_NORTH, center_east)
    alt = BASE_ALT + index * ALT_STEP

    # (frame, command, p1, p2, p3, p4, lat, lon, alt)
    items = [(FRAME_GLOBAL, CMD.MAV_CMD_NAV_WAYPOINT, 0, 0, 0, 0, home_lat, home_lon, 0),
             (FRAME_REL, CMD.MAV_CMD_NAV_TAKEOFF, 15, 0, 0, 0, home_lat, home_lon, TAKEOFF_ALT)]
    for east, north in plus_outline(HALF_BAR, ARM_TIP):
        lat, lon = offset(c_lat, c_lon, north, east)
        items.append((FRAME_REL, CMD.MAV_CMD_NAV_WAYPOINT, 0, 0, 0, 0, lat, lon, alt))
    # loop forever back to the first corner (mission index 2)
    items.append((FRAME_REL, CMD.MAV_CMD_DO_JUMP, 2, -1, 0, 0, 0, 0, 0))
    return items


def upload_mission(m, sysid, items):
    m.mav.mission_count_send(sysid, 1, len(items), 0)
    while True:
        msg = m.recv_match(type=["MISSION_REQUEST_INT", "MISSION_REQUEST", "MISSION_ACK"],
                           blocking=True, timeout=10)
        if msg is None:
            raise RuntimeError("mission upload timed out")
        if msg.get_type() == "MISSION_ACK":
            if msg.type != CMD.MAV_MISSION_ACCEPTED:
                raise RuntimeError(f"mission rejected (code {msg.type})")
            return
        frame, cmd, p1, p2, p3, p4, lat, lon, alt = items[msg.seq]
        m.mav.mission_item_int_send(sysid, 1, msg.seq, frame, cmd, 0, 1,
                                    p1, p2, p3, p4,
                                    int(lat * 1e7), int(lon * 1e7), alt, 0)


def command(m, sysid, cmd, *params, timeout=3):
    p = list(params) + [0] * (7 - len(params))
    m.mav.command_long_send(sysid, 1, cmd, 0, *p)
    deadline = time.time() + timeout
    while time.time() < deadline:
        ack = m.recv_match(type="COMMAND_ACK", blocking=True, timeout=1)
        if ack and ack.command == cmd:
            return ack.result == CMD.MAV_RESULT_ACCEPTED
    return False


def main():
    fleet_size = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    links = []

    for i in range(fleet_size):
        port = 5763 + 10 * i
        m = mavutil.mavlink_connection(f"tcp:127.0.0.1:{port}", source_system=254)
        hb = m.wait_heartbeat(timeout=10)
        if hb is None:
            sys.exit(f"IHA-{i + 1}: no heartbeat on port {port} - is SITL running?")
        sysid = m.target_system
        print(f"IHA-{sysid}: connected (port {port})")

        upload_mission(m, sysid, build_mission(i, fleet_size))
        m.mav.param_set_send(sysid, 1, b"WP_RADIUS", 30, CMD.MAV_PARAM_TYPE_INT16)
        m.mav.mission_set_current_send(sysid, 1, 1)
        m.mav.set_mode_send(sysid, CMD.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
                            m.mode_mapping()["AUTO"])
        print(f"IHA-{sysid}: + mission loaded, mode AUTO")
        links.append((m, sysid))

    # Arm everyone; retry while pre-arm checks (GPS/EKF) settle.
    pending = list(links)
    deadline = time.time() + 90
    while pending and time.time() < deadline:
        for m, sysid in list(pending):
            if command(m, sysid, CMD.MAV_CMD_COMPONENT_ARM_DISARM, 1):
                print(f"IHA-{sysid}: ARMED - taking off")
                pending.remove((m, sysid))
        if pending:
            time.sleep(2)

    if pending:
        ids = ", ".join(f"IHA-{s}" for _, s in pending)
        sys.exit(f"Could not arm: {ids} (check MAVProxy console for pre-arm messages)")
    print("All UAVs airborne, drawing +.")


if __name__ == "__main__":
    main()
