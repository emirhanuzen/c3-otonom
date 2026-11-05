"""
Receives commands from RabbitMQ and publishes their results.

Uses its own pika connection, separate from the telemetry publisher, so a
burst of telemetry confirms never delays a command and vice versa. Like the
publisher it never blocks the main loop: tick() reconnects when due (with
backoff) and otherwise processes whatever the broker has sent, with
process_data_events(time_limit=0).

Topology (docs/contracts/command-v1.md):
  - exchange otonom.command (topic, durable), shared with the core;
  - queue edge.commands (durable, x-message-ttl 10 s), bound with command.#;
  - results go to the same exchange with routing key result.<droneId>.

Commands are delivered at most once: a message is acked as soon as it arrives,
before it is carried out, so a crash in the middle can never run it twice.
A command lost that way is reported by the core as having no result.

Results are published with publisher confirms and mandatory=True. While the
broker is unreachable they wait in a bounded in-memory queue; a result that
no queue is bound for is logged and dropped.
"""

import logging
import time
from collections import deque

import pika
import pika.exceptions

from edge_adapter.publisher import CONNECTION_ERRORS, FIRST_BACKOFF_S

log = logging.getLogger(__name__)

EXCHANGE = "otonom.command"
QUEUE = "edge.commands"
COMMAND_BINDING = "command.#"
RESULT_ROUTING_KEY_PREFIX = "result."
# Must stay equal for every declaration of the queue, or the broker refuses it.
QUEUE_MESSAGE_TTL_MS = 10_000
PREFETCH = 50
MAX_PENDING_RESULTS = 1000


class CommandChannel:
    def __init__(self, host, port, user, password, max_backoff_s):
        self.parameters = pika.ConnectionParameters(
            host=host,
            port=port,
            credentials=pika.PlainCredentials(user, password),
            heartbeat=30,
            connection_attempts=1,
            socket_timeout=2,
            blocked_connection_timeout=10,
        )
        self.max_backoff_s = max_backoff_s
        self.connection = None
        self.consume_channel = None
        self.publish_channel = None
        self.next_attempt = 0.0
        self.backoff = FIRST_BACKOFF_S
        self._received = []               # command bodies acked but not yet taken
        self._results = deque()           # (drone_id, body) waiting to be published
        self.dropped_results = 0          # results lost because the queue above was full
        self.unroutable_results = 0       # results no queue was bound for

    @property
    def connected(self):
        return (self.consume_channel is not None and self.consume_channel.is_open
                and self.publish_channel is not None and self.publish_channel.is_open)

    # --- Connecting -----------------------------------------------------------
    def _connect(self):
        try:
            self.connection = pika.BlockingConnection(self.parameters)
            self.consume_channel = self.connection.channel()
            self.consume_channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
            self.consume_channel.queue_declare(queue=QUEUE, durable=True,
                                               arguments={"x-message-ttl": QUEUE_MESSAGE_TTL_MS})
            self.consume_channel.queue_bind(queue=QUEUE, exchange=EXCHANGE, routing_key=COMMAND_BINDING)
            self.consume_channel.basic_qos(prefetch_count=PREFETCH)
            self.consume_channel.basic_consume(queue=QUEUE, on_message_callback=self._on_message, auto_ack=False)
            # A separate channel for results: confirm mode makes basic_publish()
            # wait for the broker's ack, which should not involve the consumer.
            self.publish_channel = self.connection.channel()
            self.publish_channel.confirm_delivery()
        except CONNECTION_ERRORS as err:
            self._lost(f"cannot connect to {self.parameters.host}:{self.parameters.port}: {err!r}")
            return

        log.info("Command channel connected: consuming %s (%s on %s), results with confirms",
                 QUEUE, COMMAND_BINDING, EXCHANGE)
        self.backoff = FIRST_BACKOFF_S

    def _lost(self, reason):
        log.warning("RabbitMQ command channel: %s, retrying in %ss", reason, self.backoff)
        self._close_quietly()
        self.next_attempt = time.monotonic() + self.backoff
        self.backoff = min(self.backoff * 2, self.max_backoff_s)

    def _close_quietly(self):
        if self.connection is not None and self.connection.is_open:
            try:
                self.connection.close()
            except CONNECTION_ERRORS:
                pass
        self.connection = None
        self.consume_channel = None
        self.publish_channel = None

    def _on_message(self, channel, method, properties, body):
        # At most once: ack before the command is carried out.
        channel.basic_ack(delivery_tag=method.delivery_tag)
        self._received.append(body)

    # --- Called from the main loop ----------------------------------------------
    def tick(self):
        """Reconnect when due; otherwise receive waiting commands and publish waiting results."""
        if not self.connected:
            if time.monotonic() >= self.next_attempt:
                self._connect()
            return
        try:
            # Runs _on_message for every delivery that has arrived, then returns at once.
            self.connection.process_data_events(time_limit=0)
        except CONNECTION_ERRORS as err:
            self._lost(f"connection lost: {err!r}")
            return
        self._flush_results()

    def take_received(self):
        """Command bodies received since the last call, in arrival order."""
        received, self._received = self._received, []
        return received

    def publish_result(self, drone_id, body):
        """Queue one result; it is sent on this or a later tick()."""
        self._results.append((drone_id, body))
        while len(self._results) > MAX_PENDING_RESULTS:
            self._results.popleft()
            self.dropped_results += 1
            log.warning("Command result queue full (%d), oldest result dropped", MAX_PENDING_RESULTS)
        if self.connected:
            self._flush_results()

    def _flush_results(self):
        while self._results and self.connected:
            drone_id, body = self._results[0]
            routing_key = f"{RESULT_ROUTING_KEY_PREFIX}{drone_id}"
            try:
                self.publish_channel.basic_publish(
                    exchange=EXCHANGE,
                    routing_key=routing_key,
                    body=body,
                    properties=pika.BasicProperties(
                        content_type="application/json",
                        delivery_mode=pika.DeliveryMode.Persistent,
                    ),
                    mandatory=True,
                )
            except pika.exceptions.UnroutableError:
                self._results.popleft()
                self.unroutable_results += 1
                log.warning("Command result on %s is unroutable (no queue bound?), dropped", routing_key)
                continue
            except pika.exceptions.NackError:
                log.warning("RabbitMQ rejected (nack) a command result on %s, will retry", routing_key)
                return
            except CONNECTION_ERRORS as err:
                self._lost(f"result publish failed: {err!r}")
                return
            self._results.popleft()

    @property
    def pending_results(self):
        return len(self._results)

    def close(self):
        self._close_quietly()
