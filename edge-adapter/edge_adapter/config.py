"""
Configuration of the edge adapter.

Every value comes from an environment variable. The .env file in the project
root (the same file docker-compose.yml uses) is loaded first, so RabbitMQ
credentials only have to be written in one place. Variables that are already
set in the shell win over the .env file.
"""

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

# edge-adapter/edge_adapter/config.py -> parents[2] is the project root.
PROJECT_ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = PROJECT_ROOT / ".env"


@dataclass(frozen=True)
class Config:
    rabbitmq_host: str
    rabbitmq_port: int
    rabbitmq_user: str
    rabbitmq_password: str

    # With --count N, sim_vehicle.py shifts every port of instance i by
    # i * SITL_PORT_STEP. SERIAL0 (5760, 5770, ...) is taken by MAVProxy,
    # so the default base port is SERIAL1 (5762, 5772, ...).
    fleet_size: int
    sitl_base_port: int
    sitl_port_step: int

    publish_rate_hz: float  # snapshots per second, per aircraft
    stale_after_s: float    # seconds without any message before an aircraft is skipped

    # 6000 = 10 aircraft x 10 Hz x 60 s: one minute of broker outage without loss.
    outbox_max_messages: int
    outbox_flush_batch: int       # at most this many buffered messages are sent per loop
    rabbitmq_max_backoff_s: float  # upper limit of the wait between two reconnect attempts

    @property
    def connection_strings(self):
        """One MAVLink connection string per aircraft of the fleet."""
        return [f"tcp:127.0.0.1:{self.sitl_base_port + i * self.sitl_port_step}"
                for i in range(self.fleet_size)]


def _get(name, default=None):
    """Read one variable. A variable without a default is required."""
    value = os.getenv(name, default)
    if value is None or value == "":
        raise RuntimeError(f"Environment variable {name} is not set (see .env.example)")
    return value


def load_config():
    load_dotenv(ENV_FILE)
    config = Config(
        rabbitmq_host=_get("RABBITMQ_HOST", "localhost"),
        rabbitmq_port=int(_get("RABBITMQ_PORT", "5672")),
        rabbitmq_user=_get("RABBITMQ_USER"),
        rabbitmq_password=_get("RABBITMQ_PASSWORD"),
        fleet_size=int(_get("FLEET_SIZE", "10")),
        sitl_base_port=int(_get("SITL_BASE_PORT", "5762")),
        sitl_port_step=int(_get("SITL_PORT_STEP", "10")),
        publish_rate_hz=float(_get("PUBLISH_RATE_HZ", "10")),
        stale_after_s=float(_get("STALE_AFTER_S", "3")),
        outbox_max_messages=int(_get("OUTBOX_MAX_MESSAGES", "6000")),
        outbox_flush_batch=int(_get("OUTBOX_FLUSH_BATCH", "200")),
        rabbitmq_max_backoff_s=float(_get("RABBITMQ_MAX_BACKOFF_S", "5")),
    )
    if config.fleet_size < 1:
        raise RuntimeError("FLEET_SIZE must be at least 1")
    if config.publish_rate_hz <= 0:
        raise RuntimeError("PUBLISH_RATE_HZ must be greater than 0")
    if config.outbox_max_messages < 1:
        raise RuntimeError("OUTBOX_MAX_MESSAGES must be at least 1")
    if config.outbox_flush_batch < 1:
        raise RuntimeError("OUTBOX_FLUSH_BATCH must be at least 1")
    if config.rabbitmq_max_backoff_s <= 0:
        raise RuntimeError("RABBITMQ_MAX_BACKOFF_S must be greater than 0")
    return config
