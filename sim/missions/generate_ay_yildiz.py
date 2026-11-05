"""
OTONOM-C3 - Generates two ArduPlane AUTO missions that draw a crescent and star
(ay yildiz) over Elazig. Output: ay.waypoints (crescent) and yildiz.waypoints (star),
in the QGC WPL 110 text format that MAVProxy's "wp load" understands.

Geometry is built in metres on a local flat grid, then converted to lat/lon.
At this size (a few km) the flat-earth approximation is far more precise than
a fixed-wing aircraft can follow anyway.

Run:  python3 generate_ay_yildiz.py
"""

import math

# Centre of the crescent's outer circle, north-west of Elazig Airport.
CENTER_LAT = 38.6125
CENTER_LON = 39.2850
ALT_REL = 100       # metres above home
TAKEOFF_ALT = 60    # metres above home

M_PER_DEG_LAT = 111_320.0
M_PER_DEG_LON = 111_320.0 * math.cos(math.radians(CENTER_LAT))

# Crescent: outer circle (R, centre 0,0) minus inner circle (r, centre shifted right).
R_OUTER = 500.0
R_INNER = 400.0
INNER_SHIFT = 150.0
ARC_STEP_DEG = 30

# Star: pentagram to the right of the crescent, one tip pointing at it.
STAR_CENTER_X = 850.0
STAR_RADIUS = 280.0


def to_latlon(x, y):
    """Local metres (x = east, y = north) -> (lat, lon)."""
    return CENTER_LAT + y / M_PER_DEG_LAT, CENTER_LON + x / M_PER_DEG_LON


def arc(cx, cy, radius, start_deg, end_deg, step_deg):
    """Points on a circle from start to end angle (degrees)."""
    points = []
    direction = 1 if end_deg >= start_deg else -1
    angle = start_deg
    while (angle - end_deg) * direction < 0:
        a = math.radians(angle)
        points.append((cx + radius * math.cos(a), cy + radius * math.sin(a)))
        angle += direction * step_deg
    a = math.radians(end_deg)
    points.append((cx + radius * math.cos(a), cy + radius * math.sin(a)))
    return points


def crescent_points():
    # Where the two circles cross.
    x = (R_OUTER**2 - R_INNER**2 + INNER_SHIFT**2) / (2 * INNER_SHIFT)
    y = math.sqrt(R_OUTER**2 - x**2)
    outer_top = math.degrees(math.atan2(y, x))
    inner_top = math.degrees(math.atan2(y, x - INNER_SHIFT))
    # Outer arc: top crossing -> around the left -> bottom crossing.
    outer = arc(0, 0, R_OUTER, outer_top, 360 - outer_top, ARC_STEP_DEG)
    # Inner arc: bottom crossing -> back around the inner circle's left -> top crossing.
    inner = arc(INNER_SHIFT, 0, R_INNER, 360 - inner_top, inner_top, ARC_STEP_DEG)
    return outer + inner[1:]


def star_points():
    # Pentagram: 5 tips visited every second tip. First tip points left (towards the crescent).
    tips = []
    for k in range(5):
        a = math.radians(180 + k * 72)
        tips.append((STAR_CENTER_X + STAR_RADIUS * math.cos(a), STAR_RADIUS * math.sin(a)))
    return [tips[i] for i in (0, 2, 4, 1, 3)]


def write_mission(filename, points):
    """QGC WPL 110: home, takeoff, waypoints, close the shape, DO_JUMP back forever."""
    lines = ["QGC WPL 110"]
    home_lat, home_lon = to_latlon(0, 0)
    # seq current frame command p1 p2 p3 p4 lat lon alt autocontinue
    lines.append(f"0\t1\t0\t16\t0\t0\t0\t0\t{home_lat:.7f}\t{home_lon:.7f}\t0\t1")  # home (replaced by real home)
    lines.append(f"1\t0\t3\t22\t15\t0\t0\t0\t0\t0\t{TAKEOFF_ALT}\t1")               # NAV_TAKEOFF
    seq = 2
    first_wp = seq
    for x, y in points + [points[0]]:
        lat, lon = to_latlon(x, y)
        lines.append(f"{seq}\t0\t3\t16\t0\t0\t0\t0\t{lat:.7f}\t{lon:.7f}\t{ALT_REL}\t1")  # NAV_WAYPOINT
        seq += 1
    lines.append(f"{seq}\t0\t0\t177\t{first_wp}\t-1\t0\t0\t0\t0\t0\t1")  # DO_JUMP, -1 = repeat forever
    with open(filename, "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{filename}: {len(points)} shape points, {seq + 1} mission items")


if __name__ == "__main__":
    write_mission("ay.waypoints", crescent_points())
    write_mission("yildiz.waypoints", star_points())
