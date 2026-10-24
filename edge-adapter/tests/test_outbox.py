"""Tests of outbox.py: bounded FIFO buffer of unsent messages."""

import pytest

from edge_adapter.outbox import Outbox, OutboxMessage


def ids(messages):
    """Bodies of the messages, as plain strings, to compare order easily."""
    return [m.body.decode() for m in messages]


def filled(n, max_messages=100):
    outbox = Outbox(max_messages)
    for i in range(n):
        outbox.append(drone_id=i % 3 + 1, body=f"m{i}".encode())
    return outbox


def test_empty():
    outbox = Outbox(10)
    assert outbox.pending == 0
    assert outbox.dropped == 0
    assert outbox.take(5) == []


def test_fifo_order():
    outbox = filled(5)
    assert outbox.pending == 5
    assert ids(outbox.take(5)) == ["m0", "m1", "m2", "m3", "m4"]
    assert outbox.pending == 0


def test_keeps_drone_id_and_body():
    outbox = Outbox(10)
    outbox.append(7, b'{"droneId":7}')
    assert outbox.take(1) == [OutboxMessage(drone_id=7, body=b'{"droneId":7}')]


def test_full_buffer_drops_oldest_and_counts():
    outbox = filled(5, max_messages=3)
    assert outbox.pending == 3
    assert outbox.dropped == 2
    assert ids(outbox.take(10)) == ["m2", "m3", "m4"]


def test_dropped_is_a_running_total():
    outbox = filled(3, max_messages=3)
    assert outbox.dropped == 0
    outbox.append(1, b"x")
    outbox.append(1, b"y")
    assert outbox.dropped == 2
    assert outbox.pending == 3


def test_take_in_batches():
    outbox = filled(7)
    assert ids(outbox.take(3)) == ["m0", "m1", "m2"]
    assert outbox.pending == 4
    assert ids(outbox.take(3)) == ["m3", "m4", "m5"]
    assert ids(outbox.take(3)) == ["m6"]
    assert outbox.pending == 0


def test_put_back_keeps_order():
    outbox = filled(6)
    batch = outbox.take(4)                 # m0..m3
    # Pretend m0 and m1 were sent, m2 failed: m2 and m3 go back.
    outbox.put_back(batch[2:])
    assert outbox.pending == 4
    assert ids(outbox.take(10)) == ["m2", "m3", "m4", "m5"]


def test_put_back_whole_batch():
    outbox = filled(3)
    batch = outbox.take(3)
    outbox.put_back(batch)
    assert ids(outbox.take(3)) == ["m0", "m1", "m2"]
    assert outbox.dropped == 0


def test_put_back_then_append_goes_to_the_end():
    outbox = filled(2)
    outbox.put_back(outbox.take(2))
    outbox.append(1, b"new")
    assert ids(outbox.take(10)) == ["m0", "m1", "new"]


def test_put_back_over_capacity_drops_oldest():
    outbox = filled(3, max_messages=3)
    batch = outbox.take(2)                 # m0, m1
    outbox.append(1, b"m3")
    outbox.append(1, b"m4")                # buffer: m2, m3, m4 (full)
    outbox.put_back(batch)                 # m0, m1 are the oldest
    assert outbox.pending == 3
    assert outbox.dropped == 2
    assert ids(outbox.take(10)) == ["m2", "m3", "m4"]


def test_capacity_must_be_positive():
    with pytest.raises(ValueError):
        Outbox(0)
