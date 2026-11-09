"""OTONOM-C3 edge adapter: ArduPlane SITL fleet MAVLink telemetry -> JSON -> RabbitMQ."""

import os

# pymavlink picks its dialect when it is first imported. The MAVLink 2 dialect is needed
# for the mission_type field of the mission protocol (FENCE_UPLOAD uploads fence items,
# not mission items); it still reads MAVLink 1 messages. Every module imports pymavlink
# after this package, so setting it here covers the adapter and the tests.
os.environ.setdefault("MAVLINK20", "1")
