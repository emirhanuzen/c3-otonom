"""
Publishes telemetry snapshots to RabbitMQ.

Only the exchange is declared here. Queues and their bindings belong to the
consumers, which will declare them later.

Delivery is "at least once" together with the outbox (outbox.py):
  - Publisher confirms: basic_publish() returns only after the broker has
    taken responsibility for the message, so a message that was not
    confirmed is known to be unsent and stays in the outbox.
  - mandatory=True: if no queue is bound for the routing key, the broker
    returns the message instead of silently dropping it. Such a message is
    counted and logged, but not retried: without a queue there is nowhere to
    deliver it, and retrying would block the whole outbox.

The publisher never sleeps: when the broker is unreachable, it remembers when
the next attempt is due and returns, so MAVLink reading keeps going.
"""

import enum
import logging
import time

import pika
import pika.exceptions

log = logging.getLogger(__name__)

EXCHANGE = "otonom.telemetry"
ROUTING_KEY_PREFIX = "telemetry."

# Waits between two connection attempts: 1, 2, 4, ... seconds, up to max_backoff_s.
FIRST_BACKOFF_S = 1

# Errors that mean "the connection or channel is gone". StreamLostError,
# ConnectionClosedByBroker and friends are all AMQPError subclasses.
# UnroutableError and NackError are AMQPError subclasses too, so they must be
# caught before these.
CONNECTION_ERRORS = (pika.exceptions.AMQPError, OSError)


class PublishResult(enum.Enum):
    SENT = "sent"              # confirmed by the broker and routed to at least one queue
    UNROUTABLE = "unroutable"  # confirmed, but no queue matched; dropped, do not retry
    FAILED = "failed"          # not confirmed (nack or connection error); retry later


class Publisher:
    def __init__(self, host, port, user, password, max_backoff_s):
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
        self.max_backoff_s = max_backoff_s
        self.connection = None
        self.channel = None
        self.next_attempt = 0.0
        self.backoff = FIRST_BACKOFF_S
        self.unroutable = 0          # total unroutable messages since start
        self._routing_ok = True      # used to log only when unroutable starts/stops

    @property
    def connected(self):
        return self.channel is not None and self.channel.is_open

    # --- Connecting -----------------------------------------------------------
    def _connect(self):
        try:
            self.connection = pika.BlockingConnection(self.parameters)
            self.channel = self.connection.channel()
            # From now on every basic_publish() waits for the broker's ack/nack.
            self.channel.confirm_delivery()
            # durable: the exchange survives a broker restart.
            self.channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
        except CONNECTION_ERRORS as err:
            self._lost(f"cannot connect to {self.parameters.host}:{self.parameters.port}: {err!r}")
            return

        log.info("Connected to RabbitMQ at %s:%s, exchange %s declared, confirms enabled",
                 self.parameters.host, self.parameters.port, EXCHANGE)
        self.backoff = FIRST_BACKOFF_S

    def _lost(self, reason):
        """Forget the broken connection and schedule the next attempt."""
        log.warning("RabbitMQ: %s, retrying in %ss", reason, self.backoff)
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
        """Publish one JSON snapshot and wait for the broker's confirm."""
        if not self.connected:
            return PublishResult.FAILED
        routing_key = f"{ROUTING_KEY_PREFIX}{drone_id}"
        try:
            self.channel.basic_publish(
                exchange=EXCHANGE,
                routing_key=routing_key,
                body=body,
                properties=pika.BasicProperties(
                    content_type="application/json",
                    # Persistent: written to disk by a durable queue, so it
                    # survives a broker restart once a queue exists.
                    delivery_mode=pika.DeliveryMode.Persistent,
                ),
                # mandatory: return the message to us if no queue matches,
                # instead of dropping it silently.
                mandatory=True,
            )
        except pika.exceptions.UnroutableError:
            # The connection is fine; there is just no queue for this key yet.
            self.unroutable += 1
            if self._routing_ok:
                log.warning("Unroutable message on %s (no queue bound?), dropping "
                            "unroutable messages until a queue appears", routing_key)
                self._routing_ok = False
            return PublishResult.UNROUTABLE
        except pika.exceptions.NackError:
            # The broker refused the message (internal error). The connection
            # is still usable, so only this message counts as unsent.
            log.warning("RabbitMQ rejected (nack) a message on %s, will retry", routing_key)
            return PublishResult.FAILED
        except CONNECTION_ERRORS as err:
            self._lost(f"publish failed: {err!r}")
            return PublishResult.FAILED

        if not self._routing_ok:
            log.info("Messages are routed to a queue again")
            self._routing_ok = True
        return PublishResult.SENT

    def close(self):
        self._close_quietly()
