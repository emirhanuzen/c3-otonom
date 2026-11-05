"""Tests of command_channel.py with mocked pika objects (no RabbitMQ needed)."""

from types import SimpleNamespace
from unittest import mock

import pika
import pika.exceptions
import pytest

from edge_adapter import command_channel
from edge_adapter.command_channel import CommandChannel


@pytest.fixture
def connected():
    """A CommandChannel whose pika connection is a mock that connects at once."""
    connection = mock.MagicMock()
    consume, publish = mock.MagicMock(), mock.MagicMock()
    consume.is_open = publish.is_open = True
    connection.channel.side_effect = [consume, publish]
    with mock.patch.object(command_channel.pika, "BlockingConnection", return_value=connection):
        channel = CommandChannel("localhost", 5672, "user", "secret", max_backoff_s=5)
        channel.tick()
    assert channel.connected
    return channel, connection, consume, publish


def test_declares_topology_and_consumes_with_manual_ack(connected):
    channel, connection, consume, publish = connected

    consume.exchange_declare.assert_called_once_with(exchange="otonom.command", exchange_type="topic", durable=True)
    consume.queue_declare.assert_called_once_with(queue="edge.commands", durable=True,
                                                  arguments={"x-message-ttl": 10000})
    consume.queue_bind.assert_called_once_with(queue="edge.commands", exchange="otonom.command",
                                               routing_key="command.#")
    consume.basic_consume.assert_called_once()
    assert consume.basic_consume.call_args.kwargs["auto_ack"] is False
    publish.confirm_delivery.assert_called_once()


def test_message_is_acked_before_it_is_handed_over(connected):
    channel, connection, consume, publish = connected
    events = []
    consume.basic_ack.side_effect = lambda delivery_tag: events.append(("ack", delivery_tag))

    channel._on_message(consume, SimpleNamespace(delivery_tag=7), None, b"cmd")
    events.append(("taken", channel.take_received()))

    assert events == [("ack", 7), ("taken", [b"cmd"])]
    assert channel.take_received() == []


def test_tick_processes_events_without_blocking(connected):
    channel, connection, consume, publish = connected
    channel.tick()
    connection.process_data_events.assert_called_with(time_limit=0)


def test_result_is_published_with_routing_key_per_drone(connected):
    channel, connection, consume, publish = connected

    channel.publish_result(3, b'{"status":"ACCEPTED"}')

    kwargs = publish.basic_publish.call_args.kwargs
    assert kwargs["exchange"] == "otonom.command"
    assert kwargs["routing_key"] == "result.3"
    assert kwargs["body"] == b'{"status":"ACCEPTED"}'
    assert kwargs["mandatory"] is True
    assert kwargs["properties"].delivery_mode == pika.DeliveryMode.Persistent.value
    assert channel.pending_results == 0


def test_unroutable_result_is_dropped(connected):
    channel, connection, consume, publish = connected
    publish.basic_publish.side_effect = pika.exceptions.UnroutableError([])

    channel.publish_result(3, b"r1")

    assert channel.pending_results == 0
    assert channel.unroutable_results == 1


def test_nacked_result_stays_queued(connected):
    channel, connection, consume, publish = connected
    publish.basic_publish.side_effect = pika.exceptions.NackError([])

    channel.publish_result(3, b"r1")

    assert channel.pending_results == 1


def test_results_wait_while_disconnected_and_go_out_in_order():
    connection = mock.MagicMock()
    consume, publish = mock.MagicMock(), mock.MagicMock()
    consume.is_open = publish.is_open = True
    connection.channel.side_effect = [consume, publish]
    with mock.patch.object(command_channel.pika, "BlockingConnection",
                           side_effect=[pika.exceptions.AMQPConnectionError("down"), connection]):
        channel = CommandChannel("localhost", 5672, "user", "secret", max_backoff_s=5)
        channel.tick()                       # first attempt fails
        assert not channel.connected
        channel.publish_result(1, b"r1")
        channel.publish_result(2, b"r2")
        assert channel.pending_results == 2

        channel.next_attempt = 0             # backoff elapsed
        channel.tick()                       # connects
        channel.tick()                       # flushes

    assert [c.kwargs["routing_key"] for c in publish.basic_publish.call_args_list] == ["result.1", "result.2"]
    assert channel.pending_results == 0


def test_connection_loss_schedules_reconnect_with_backoff(connected):
    channel, connection, consume, publish = connected
    connection.process_data_events.side_effect = pika.exceptions.StreamLostError("gone")

    channel.tick()

    assert not channel.connected
    assert channel.backoff == 2
