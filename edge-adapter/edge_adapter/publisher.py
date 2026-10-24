"""
Publishes telemetry snapshots to RabbitMQ.

Only the exchange is declared here. Queues and their bindings belong to the
consumers, which will declare them later. Until then RabbitMQ drops the
messages, because no queue is bound to the exchange.

The publisher never sleeps: when the broker is unreachable, it remembers when
the next attempt is due and returns, so MAVLink reading keeps going.
"""

import logging
import time

import pika
import pika.exceptions

log = logging.getLogger(__name__)

EXCHANGE = "otonom.telemetry"
ROUTING_KEY_PREFIX = "telemetry."

# Waits between two connection attempts: 1, 2, 4, 8, 16, 30, 30, ... seconds.
FIRST_BACKOFF_S = 1
MAX_BACKOFF_S = 30

# Errors that mean "the connection or channel is gone". StreamLostError,
# ConnectionClosedByBroker and friends are all AMQPError subclasses.
CONNECTION_ERRORS = (pika.exceptions.AMQPError, OSError)


class Publisher:
    def __init__(self, host, port, user, password):
        self.parameters = pika.ConnectionParameters(
            host=host,
            port=port,
            credentials=pika.PlainCredentials(user, password),
            heartbeat=30,
            # One quick attempt per call. The retry schedule is ours (see tick()),
            # because pika's own retries would sleep and block the main loop.
            connection_attempts=1,
            socket_timeout=2,
            blocked_connection_timeout=10,
        )
        self.connection = None
        self.channel = None
        self.next_attempt = 0.0
        self.backoff = FIRST_BACKOFF_S

    @property
    def connected(self):
        return self.channel is not None and self.channel.is_open

    # --- Connecting -----------------------------------------------------------
    def _connect(self):
        try:
            self.connection = pika.BlockingConnection(self.parameters)
            self.channel = self.connection.channel()
            # durable: the exchange survives a broker restart.
            self.channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
        except CONNECTION_ERRORS as err:
            self._lost(f"cannot connect to {self.parameters.host}:{self.parameters.port}: {err!r}")
            return

        log.info("Connected to RabbitMQ at %s:%s, exchange %s declared",
                 self.parameters.host, self.parameters.port, EXCHANGE)
        self.backoff = FIRST_BACKOFF_S

    def _lost(self, reason):
        """Forget the broken connection and schedule the next attempt."""
        log.warning("RabbitMQ: %s, retrying in %ss", reason, self.backoff)
        self._close_quietly()
        self.next_attempt = time.monotonic() + self.backoff
        self.backoff = min(self.backoff * 2, MAX_BACKOFF_S)

    def _close_quietly(self):
        if self.connection is not None and self.connection.is_open:
            try:
                self.connection.close()
            except CONNECTION_ERRORS:
                pass
        self.connection = None
        self.channel = None

    # --- Called from the main loop ----------------------------------------------
    def tick(self):
        """Reconnect when due, otherwise let pika handle heartbeats and broker frames."""
        if not self.connected:
            if time.monotonic() >= self.next_attempt:
                self._connect()
            return

        try:
            # time_limit=0 processes whatever is waiting and returns at once.
            # Without this call pika would never answer broker heartbeats and
            # the broker would close the connection.
            self.connection.process_data_events(time_limit=0)
        except CONNECTION_ERRORS as err:
            self._lost(f"connection lost: {err!r}")

    def publish(self, drone_id, body):
        """Publish one JSON snapshot. Returns False if it could not be sent."""
        if not self.connected:
            return False
        try:
            self.channel.basic_publish(
                exchange=EXCHANGE,
                routing_key=f"{ROUTING_KEY_PREFIX}{drone_id}",
                body=body,
                properties=pika.BasicProperties(
                    content_type="application/json",
                    # Persistent: written to disk by a durable queue, so it
                    # survives a broker restart once a queue exists.
                    delivery_mode=pika.DeliveryMode.Persistent,
                ),
            )
        except CONNECTION_ERRORS as err:
            self._lost(f"publish failed: {err!r}")
            return False
        return True

    def close(self):
        self._close_quietly()
