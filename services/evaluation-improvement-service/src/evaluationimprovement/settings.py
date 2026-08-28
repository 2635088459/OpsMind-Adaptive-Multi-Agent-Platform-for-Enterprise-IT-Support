"""SPEC-EI-002: connection settings for the shared Postgres instance. Mirrors the
sibling memory-knowledge-service's/ticket-workflow-service's own env var names and
local-dev defaults exactly (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`,
matching `infrastructure/docker-compose/local-platform.yml`'s single shared Postgres
container) — Evaluation Improvement tables live in their own `evaluation` schema
inside that same database (07-data-model §"Schema Ownership": `evaluation.*`), not a
separate database. Real RabbitMQ settings are SPEC-EI-003 scope — kept out of this
file until an adapter actually reads them.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    evaluation_service_name: str = "evaluation-improvement-service"

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "ticket_workflow"
    db_username: str = "ticket_workflow"
    db_password: str = "ticket_workflow"

    # SPEC-EI-001's in-memory adapters remain available (fast, hermetic unit/
    # application tests construct them directly via tests/conftest.py's own
    # `container` fixture, which explicitly passes evaluation_persistence="memory"
    # regardless of this default). "postgres" is the default here for the same
    # reason memory-knowledge-service's own memory_persistence defaults to
    # "postgres": a schema-baseline service that silently falls back to non-durable
    # storage in production is a worse failure mode than refusing to boot without a
    # reachable database.
    evaluation_persistence: Literal["memory", "postgres"] = "postgres"

    # 12-observability: mirrors memory-knowledge-service's own settings.py field
    # names/defaults exactly. "console" is genuinely functional (exports to stdout,
    # no network calls), not a stub — "otlp" is an explicit opt-in for a real
    # collector.
    otel_exporter: Literal["console", "otlp"] = "console"
    otel_exporter_otlp_endpoint: str = "localhost:4317"
    otel_service_name: str = "evaluation-improvement-service"

    @property
    def sqlalchemy_url(self) -> str:
        return f"postgresql+psycopg://{self.db_username}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
