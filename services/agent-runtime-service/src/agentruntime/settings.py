"""SPEC-ARO-002 persistence: connection settings for the shared Postgres instance.
Mirrors the sibling `ticket-workflow-service`'s env var names and local-dev
defaults exactly (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`,
matching `infrastructure/docker-compose/local-platform.yml`'s single shared
Postgres container) — Runtime tables live in their own `agent_runtime` schema
inside that same database (07-data-model §"Schema": "Use a dedicated schema:
agent_runtime. Runtime tables must not be mixed into Ticket Workflow tables"),
not a separate database.
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

    # SPEC-ARO-001's in-memory adapters remain available (fast, hermetic unit
    # tests use them directly, bypassing agentruntime.container entirely).
    # "postgres" is the default here because a schema-baseline service that
    # silently falls back to non-durable storage in production is a worse
    # failure mode than refusing to boot without a reachable database.
    agent_runtime_persistence: Literal["memory", "postgres"] = "postgres"

    # SPEC-ARO-025 08-transaction-and-outbox §"Outbox Publisher": mirrors
    # infrastructure/docker-compose/local-platform.yml's opsmind-rabbitmq env var
    # names/defaults (RABBITMQ_USERNAME/RABBITMQ_PASSWORD/RABBITMQ_PORT).
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "guest"
    rabbitmq_password: str = "guest"
    rabbitmq_vhost: str = "/"
    rabbitmq_exchange: str = "agent-runtime-events"

    # Unlike agent_runtime_persistence, "logging" (not "rabbitmq") is the default:
    # every hermetic unit test that boots the container without overriding this
    # setting must not silently attempt a real broker connection — a service that
    # merely *logs* an unpublished event on an unconfigured host is a safe,
    # inert default, unlike falling back to non-durable storage.
    event_publisher_adapter: Literal["logging", "rabbitmq"] = "logging"

    # SPEC-ARO-034 12-observability: OpenTelemetry Python is explicitly frozen in
    # docs/low-level-design/shared/technology-baseline §10 "System Observability",
    # but no live OTel Collector container exists in this repo's own docker-compose
    # yet — "console" (spans/metrics logged locally, not shipped anywhere) is the
    # safe default for hermetic tests and local runs, mirroring
    # event_publisher_adapter's own "logging"-by-default reasoning exactly. "otlp"
    # is available for when a real Collector is reachable.
    otel_exporter: Literal["console", "otlp"] = "console"
    otel_exporter_otlp_endpoint: str = "localhost:4317"
    otel_service_name: str = "agent-runtime-service"

    @property
    def sqlalchemy_url(self) -> str:
        return f"postgresql+psycopg://{self.db_username}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
