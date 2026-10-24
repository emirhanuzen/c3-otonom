"""
Local buffer of messages waiting to be published.

Snapshots go into the outbox first and leave it only after RabbitMQ has
confirmed them. While the broker is unreachable they pile up here, so a short
outage loses nothing. The buffer is bounded: when it is full, the oldest
message is dropped, because fresh telemetry is worth more than stale one.
Dropped messages leave a gap in their aircraft's seq numbers, which tells
consumers that something was lost.

The buffer lives in memory only. If the adapter process dies, its content is
lost (known limitation, see docs/adr/0001-kenar-adaptoru-ve-rabbitmq.md).

Standard library only: this module knows nothing about RabbitMQ or MAVLink.
"""

from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class OutboxMessage:
    drone_id: int
    body: bytes  # the JSON snapshot, already serialized


class Outbox:
    def __init__(self, max_messages):
        if max_messages < 1:
            raise ValueError("max_messages must be at least 1")
        self.max_messages = max_messages
        # No deque(maxlen=...): it would silently drop from the wrong end in
        # put_back(), and we want to count every dropped message.
        self._queue = deque()
        self._dropped = 0

    @property
    def pending(self):
        """Number of messages waiting to be sent."""
        return len(self._queue)

    @property
    def dropped(self):
        """Total number of messages dropped because the buffer was full."""
        return self._dropped

    def append(self, drone_id, body):
        """Add a message at the end. Drops the oldest one if the buffer is full."""
        self._queue.append(OutboxMessage(drone_id, body))
        self._trim()

    def take(self, n):
        """Remove and return up to n messages from the front, oldest first."""
        count = min(n, len(self._queue))
        return [self._queue.popleft() for _ in range(count)]

    def put_back(self, messages):
        """Return messages from take() to the front, keeping their order.

        `messages` must be in the order take() gave them (oldest first).
        """
        # extendleft() adds one by one to the front, which reverses the
        # sequence, so we feed it reversed to keep the original order.
        self._queue.extendleft(reversed(messages))
        self._trim()

    def _trim(self):
        while len(self._queue) > self.max_messages:
            self._queue.popleft()
            self._dropped += 1
