"""
OTONOM-C3 - First experiment: read live telemetry from ArduPlane SITL.

What this script does:
  1. Connects to the simulated aircraft over MAVLink.
  2. Asks the autopilot to start streaming telemetry.
  3. Prints position, battery, GPS and flight mode in a readable form.

How to run (inside the Ubuntu / WSL terminal, while SITL is running):
  source ~/venv-ardupilot/bin/activate
  cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/experiments
  python3 read_telemetry.py

Stop with Ctrl+C.
"""

from pymavlink import mavutil

# SITL opens several TCP ports. MAVProxy (the console + map) already uses 5760,
# so we connect to SERIAL1 on port 5762. This is a second, independent link
# to the same aircraft - exactly how a second ground station would connect.
CONNECTION_STRING = "tcp:127.0.0.1:5762"

print(f"Connecting to {CONNECTION_STRING} ...")
conn = mavutil.mavlink_connection(CONNECTION_STRING)

# Block until the autopilot sends its first HEARTBEAT.
# A heartbeat is a small "I am alive" message sent about once per second.
# Receiving one proves the link works and tells us the aircraft's system ID.
conn.wait_heartbeat()
print(f"Connected: system {conn.target_system}, component {conn.target_component}")

# On a new link the autopilot may send only heartbeats. Here we ask it to
# stream all standard telemetry at 4 messages per second (4 Hz).
conn.mav.request_data_stream_send(
    conn.target_system,
    conn.target_component,
    mavutil.mavlink.MAV_DATA_STREAM_ALL,
    4,  # rate in Hz
    1,  # 1 = start streaming, 0 = stop
)

# The MAVLink message types we care about for now.
WANTED = ["HEARTBEAT", "GLOBAL_POSITION_INT", "SYS_STATUS", "GPS_RAW_INT"]

try:
    while True:
        # Wait until one of the wanted messages arrives, ignore everything else.
        msg = conn.recv_match(type=WANTED, blocking=True)
        kind = msg.get_type()

        if kind == "HEARTBEAT":
            # Only use heartbeats from the autopilot itself, not from other
            # ground stations that may also be sending heartbeats.
            if msg.get_srcSystem() != conn.target_system:
                continue
            mode = mavutil.mode_string_v10(msg)
            armed = bool(msg.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED)
            print(f"[MODE] {mode:<10} armed={armed}")

        elif kind == "GLOBAL_POSITION_INT":
            # MAVLink sends numbers as integers to save bandwidth:
            #   lat/lon are degrees * 10^7, altitudes are in millimetres,
            #   hdg is degrees * 100.
            lat = msg.lat / 1e7
            lon = msg.lon / 1e7
            alt_msl = msg.alt / 1000           # metres above sea level
            alt_rel = msg.relative_alt / 1000  # metres above the home point
            heading = msg.hdg / 100
            print(f"[POS ] lat={lat:.6f} lon={lon:.6f} "
                  f"alt_msl={alt_msl:.1f}m alt_rel={alt_rel:.1f}m hdg={heading:.0f}")

        elif kind == "SYS_STATUS":
            # battery_remaining is a percentage (-1 means unknown),
            # voltage_battery is in millivolts.
            volts = msg.voltage_battery / 1000
            print(f"[BATT] {msg.battery_remaining}%  {volts:.2f}V")

        elif kind == "GPS_RAW_INT":
            # fix_type: 0-1 = no fix, 2 = 2D, 3 = 3D, higher = better.
            # This will matter later for GPS spoofing detection.
            print(f"[GPS ] fix_type={msg.fix_type} satellites={msg.satellites_visible}")

except KeyboardInterrupt:
    print("\nStopped.")
