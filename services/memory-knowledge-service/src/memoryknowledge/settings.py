"""SPEC-MK-002: connection settings for the shared Postgres instance. Mirrors the
sibling ticket-workflow-service's and agent-runtime-service's own env var names and
local-dev defaults exactly (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`,
matching `infrastructure/docker-compose/local-platform.yml`'s single shared Postgres
container) — Memory tables live in their own `memory` schema inside that same
database (07-data-model §"Schema": logical schema `memory`), not a separate database.
event_publisher_adapter stays a single-value Literal today so its *shape* is already
in place for SPEC-MK-003 (outbox/idempotency/audit baseline) to add "rabbitmq" without
a breaking rename — mirrors agent-runtime-service's own settings.py evolution.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "ticket_workflow"
    db_username: str = "ticket_workflow"
    db_password: str = "ticket_workflow"

    # SPEC-MK-001's in-memory adapters remain available (fast, hermetic unit tests use
    # them directly, bypassing memoryknowledge.container entirely). "postgres" is the
    # default here for the same reason agent-runtime-service's own
    # agent_runtime_persistence defaults to "postgres": a schema-baseline service that
    # silently falls back to non-durable storage in production is a worse failure mode
    # than refusing to boot without a reachable database.
    memory_persistence: Literal["memory", "postgres"] = "postgres"

    event_publisher_adapter: Literal["logging"] = "logging"
    """SPEC-MK-003 will add "rabbitmq" here alongside the real broker adapter
    (08-transaction-and-outbox). "logging" is a safe, inert default for hermetic tests
    and local runs — mirrors agent-runtime-service's own event_publisher_adapter default.
    """

    memory_service_name: str = "memory-knowledge-service"

    @property
    def sqlalchemy_url(self) -> str:
        return f"postgresql+psycopg://{self.db_username}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
