"""
OTONOM-C3 edge adapter: entry point.

What it does, in a single loop (no threads):
  1. Polls the MAVLink link of every aircraft without blocking.
  2. Keeps the RabbitMQ connection alive, reconnecting with backoff if needed.
  3. PUBLISH_RATE_HZ times per second, puts one JSON snapshot into the outbox
     for every aircraft heard from in the last STALE_AFTER_S seconds.
  4. While RabbitMQ is up, sends buffered snapshots in order, at most
     OUTBOX_FLUSH_BATCH per loop, each one confirmed by the broker.
  5. Every 10 seconds, logs how many messages each aircraft got published.

Data flow of one snapshot:
  mavlink_reader (raw MAVLink messages) -> mavlink_mapper (DroneTelemetry)
  -> schema (JSON) -> outbox (local buffer) -> publisher (RabbitMQ)

How to run (inside the Ubuntu / WSL terminal, while SITL and RabbitMQ are running):
  cd /mnt/c/Users/Emirhan/Desktop/otonom-c3/edge-adapter
  source ~/venv-edge/bin/activate
  python -m edge_adapter.main

Stop with Ctrl+C.
"""

import logging
import time
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone

from edge_adapter.config import load_config
from edge_adapter.mavlink_mapper import to_telemetry
from edge_adapter.mavlink_reader import MavlinkReader
from edge_adapter.outbox import Outbox
from edge_adapter.publisher import PublishResult, Publisher
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
    # seq restarts at 0 on every run, so (droneId, sessionId, seq) identifies a message.
    session_id = str(uuid.uuid4())
    log.info("Session %s", session_id)
    log.info("Fleet of %d aircraft on %s, publishing at %s Hz",
             config.fleet_size, ", ".join(config.connection_strings), config.publish_rate_hz)

    # The telemetry stream is requested at the publish rate, so every
    # snapshot carries fresh values instead of repeating the previous ones.
    reader = MavlinkReader(config.connection_strings, stream_rate_hz=config.publish_rate_hz)
    publisher = Publisher(config.rabbitmq_host, config.rabbitmq_port,
                          config.rabbitmq_user, config.rabbitmq_password,
                          max_backoff_s=config.rabbitmq_max_backoff_s)
    outbox = Outbox(config.outbox_max_messages)

    publish_period = 1 / config.publish_rate_hz
    seq = defaultdict(int)       # next sequence number per aircraft, starts at 0
    published = Counter()        # messages published per aircraft since the last summary
    summary = Summary(outbox, publisher)
    next_publish = time.monotonic()
    next_summary = next_publish + SUMMARY_INTERVAL_S

    try:
        while True:
            reader.poll()
            publisher.tick()
            now = time.monotonic()

            if now >= next_publish:
                # Snapshots are produced even while RabbitMQ is down: they wait
                # in the outbox. seq and timestamp are fixed here, so a message
                # sent late still tells when it was measured.
                timestamp = datetime.now(timezone.utc)
                for drone_id, state in reader.fresh_drones(config.stale_after_s):
                    telemetry = to_telemetry(drone_id, state)
                    outbox.append(drone_id, to_json(build_snapshot(telemetry, session_id, seq[drone_id], timestamp)))
                    seq[drone_id] += 1
                next_publish += publish_period
                # After a long stall (for example, a slow reconnect) do not try
                # to catch up with a burst of snapshots, just restart the beat.
                if next_publish < now:
                    next_publish = now + publish_period

            if publisher.connected and outbox.pending:
                flush(outbox, publisher, config.outbox_flush_batch, published)

            if now >= next_summary:
                summary.log(reader, published)
                published.clear()
                next_summary += SUMMARY_INTERVAL_S

            time.sleep(LOOP_SLEEP_S)

    except KeyboardInterrupt:
        log.info("Stopping (Ctrl+C), %d buffered messages are discarded", outbox.pending)

    finally:
        reader.close()
        publisher.close()
        log.info("All connections closed")


def flush(outbox, publisher, batch_size, published):
    """Send up to batch_size buffered messages, oldest first.

    On the first message that could not be sent, it and everything after it
    go back to the front of the outbox in the same order, and sending stops
    until the next loop. This keeps the per-aircraft order intact.
    """
    batch = outbox.take(batch_size)
    for i, message in enumerate(batch):
        result = publisher.publish(message.drone_id, message.body)
        if result is PublishResult.FAILED:
            outbox.put_back(batch[i:])
            return
        if result is PublishResult.SENT:
            published[message.drone_id] += 1
        # UNROUTABLE: counted by the publisher, the message is gone.


class Summary:
    """Builds the 10-second log line. Outbox and publisher counters are totals
    since start, so we remember the previous values to report the difference."""

    def __init__(self, outbox, publisher):
        self.outbox = outbox
        self.publisher = publisher
        self.last_dropped = 0
        self.last_unroutable = 0

    def log(self, reader, published):
        dropped = self.outbox.dropped - self.last_dropped
        unroutable = self.publisher.unroutable - self.last_unroutable
        self.last_dropped = self.outbox.dropped
        self.last_unroutable = self.publisher.unroutable

        # Every aircraft ever heard from is listed, so a silent one shows up as 0.
        counts = " ".join(f"IHA-{sysid}={published[sysid]}" for sysid in sorted(reader.fleet))
        rabbit = "up" if self.publisher.connected else "DOWN"
        log.info("Published in last %ss: %s | outbox pending=%d dropped=%d unroutable=%d (RabbitMQ %s)",
                 SUMMARY_INTERVAL_S, counts or "no aircraft yet",
                 self.outbox.pending, dropped, unroutable, rabbit)


if __name__ == "__main__":
    run()
