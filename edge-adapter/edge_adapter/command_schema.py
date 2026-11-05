"""
JSON wire format of commands and results, schema version 1.

The contract is documented in docs/contracts/command-v1.md. This module only
depends on the domain model (command.py), never on MAVLink or RabbitMQ.
"""

import json
import uuid
from datetime import datetime, timezone

from edge_adapter.command import Command, CommandType, InvalidParams, validate_params
from edge_adapter.schema import format_timestamp

SCHEMA_VERSION = 1


class InvalidCommand(ValueError):
    """A command message that cannot be carried out.

    command_id and drone_id are filled in when the message carried them, so a
    REJECTED result can still be sent. Without a drone_id there is no routing
    key for the result and the message can only be logged.
    """

    def __init__(self, detail, command_id=None, drone_id=None, command_type=None):
        super().__init__(detail)
        self.detail = detail
        self.command_id = command_id
        self.drone_id = drone_id
        self.command_type = command_type  # the raw "type" string, for logging


def parse_command(body):
    """Bytes of one command message -> Command. Raises InvalidCommand."""
    try:
        data = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as err:
        raise InvalidCommand(f"invalid JSON: {err}") from None
    if not isinstance(data, dict):
        raise InvalidCommand("the message is not a JSON object")

    drone_id = data.get("droneId")
    if isinstance(drone_id, bool) or not isinstance(drone_id, int) or drone_id < 1:
        drone_id = None
    command_id = _parse_uuid(data.get("commandId"))
    raw_type = data.get("type") if isinstance(data.get("type"), str) else None

    def invalid(detail):
        return InvalidCommand(detail, command_id, drone_id, raw_type)

    # Without a droneId there is no routing key to answer on; otherwise errors are answered.
    if drone_id is None:
        raise invalid("droneId (a positive integer) is required")
    if data.get("schemaVersion") != SCHEMA_VERSION:
        raise invalid(f"unsupported schemaVersion {data.get('schemaVersion')!r}")
    if command_id is None:
        raise invalid("commandId (a UUID) is required")

    issued_at = _parse_timestamp(data.get("issuedAt"))
    if issued_at is None:
        raise invalid("issuedAt (an ISO 8601 UTC timestamp) is required")

    try:
        command_type = CommandType(data.get("type"))
    except ValueError:
        raise invalid(f"unknown command type {data.get('type')!r}") from None

    try:
        params = validate_params(command_type, data.get("params", {}))
    except InvalidParams as err:
        raise invalid(f"invalid params for {command_type.value}: {err}") from None

    return Command(command_id=command_id, drone_id=drone_id, type=command_type,
                   params=params, issued_at=issued_at)


def build_result(result):
    """CommandResult -> result dictionary."""
    return {
        "schemaVersion": SCHEMA_VERSION,
        "commandId": result.command_id,
        "droneId": result.drone_id,
        "status": result.status.value,
        "detail": result.detail,
        "completedAt": format_timestamp(result.completed_at),
    }


def result_to_json(result):
    """CommandResult -> compact UTF-8 JSON bytes, ready to publish."""
    return json.dumps(build_result(result), separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _parse_uuid(value):
    if not isinstance(value, str):
        return None
    try:
        return str(uuid.UUID(value))
    except ValueError:
        return None


def _parse_timestamp(value):
    """ISO 8601 string with a UTC offset -> aware datetime, or None.

    A timestamp without an offset is refused: it could be in any time zone.
    """
    if not isinstance(value, str):
        return None
    try:
        # fromisoformat() accepts "Z" from Python 3.11 on; replacing it keeps 3.10 working.
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)
