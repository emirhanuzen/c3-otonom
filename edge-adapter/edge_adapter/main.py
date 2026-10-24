"""
OTONOM-C3 edge adapter: entry point.

What it does, in a single loop (no threads):
  1. Polls the MAVLink link of every aircraft without blocking.
  2. Keeps the RabbitMQ connection alive, reconnecting with backoff if needed.
  3. PUBLISH_RATE_HZ times per second, publishes one JSON snapshot for every
     aircraft heard from in the last STALE_AFTER_S seconds.
  4. Every 10 seconds, logs how many messages each aircraft got published.

How to run (inside the Ubuntu / WSL terminal, while SITL and RabbitMQ are running):
  cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/edge-adapter
  source .venv/bin/activate
  python -m edge_adapter.main

Stop with Ctrl+C.
"""

import logging
import time
from collections import Counter, defaultdict

from edge_adapter.config import load_config
from edge_adapter.mavlink_reader import MavlinkReader
from edge_adapter.publisher import Publisher
from edge_adapter.schema import build_snapshot, to_json

log = logging.getLogger("edge_adapter")

SUMMARY_INTERVAL_S = 10
# Without a short pause the loop would spin at 100% CPU doing nothing.
# 5 ms is far shorter than the 100 ms between two snapshots at 10 Hz.
LOOP_SLEEP_S = 0.005


def run():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    # pika logs every connection failure itself at ERROR/WARNING level; our
    # publisher already reports them in one line.
    logging.getLogger("pika").setLevel(logging.CRITICAL)

    config = load_config()
    log.info("Fleet of %d aircraft on %s, publishing at %s Hz",
             config.fleet_size, ", ".join(config.connection_strings), config.publish_rate_hz)

    # The telemetry stream is requested at the publish rate, so every
    # snapshot carries fresh values instead of repeating the previous ones.
    reader = MavlinkReader(config.connection_strings, stream_rate_hz=config.publish_rate_hz)
    publisher = Publisher(config.rabbitmq_host, config.rabbitmq_port,
                          config.rabbitmq_user, config.rabbitmq_password)

    publish_period = 1 / config.publish_rate_hz
    seq = defaultdict(int)       # next sequence number per aircraft, starts at 0
    published = Counter()        # messages published per aircraft since the last summary
    next_publish = time.monotonic()
    next_summary = next_publish + SUMMARY_INTERVAL_S

    try:
        while True:
            reader.poll()
            publisher.tick()
            now = time.monotonic()

            if now >= next_publish:
                if publisher.connected:
                    for drone_id, state in reader.fresh_drones(config.stale_after_s):
                        body = to_json(build_snapshot(drone_id, state, seq[drone_id]))
                        # seq grows even if this publish fails, so consumers can
                        # see the gap and know that snapshots were lost.
                        seq[drone_id] += 1
                        if publisher.publish(drone_id, body):
                            published[drone_id] += 1
                next_publish += publish_period
                # After a long stall (for example, a slow reconnect) do not try
                # to catch up with a burst of snapshots, just restart the beat.
                if next_publish < now:
                    next_publish = now + publish_period

            if now >= next_summary:
                log_summary(reader, publisher, published)
                published.clear()
                next_summary += SUMMARY_INTERVAL_S

            time.sleep(LOOP_SLEEP_S)

    except KeyboardInterrupt:
        log.info("Stopping (Ctrl+C)")

    finally:
        reader.close()
        publisher.close()
        log.info("All connections closed")


def log_summary(reader, publisher, published):
    """One line: messages published in the last interval, per known aircraft."""
    # Every aircraft ever heard from is listed, so a silent one shows up as 0.
    counts = " ".join(f"IHA-{sysid}={published[sysid]}" for sysid in sorted(reader.fleet))
    rabbit = "up" if publisher.connected else "DOWN"
    log.info("Published in last %ss: %s (RabbitMQ %s)",
             SUMMARY_INTERVAL_S, counts or "no aircraft yet", rabbit)


if __name__ == "__main__":
    run()
