"""SPEC-MK-002: connection settings for the shared Postgres instance. Mirrors the
sibling ticket-workflow-service's and agent-runtime-service's own env var names and
local-dev defaults exactly (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`,
matching `infrastructure/docker-compose/local-platform.yml`'s single shared Postgres
container) — Memory tables live in their own `memory` schema inside that same
database (07-data-model §"Schema": logical schema `memory`), not a separate database.
SPEC-MK-031 adds the `rabbitmq_*` fields and widens `event_publisher_adapter` to a
real "rabbitmq" option — mirrors agent-runtime-service's own settings.py exactly.
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

    # SPEC-MK-031 08-transaction-and-outbox §"Outbox Publisher": mirrors
    # infrastructure/docker-compose/local-platform.yml's opsmind-rabbitmq env var
    # names/defaults (RABBITMQ_USERNAME/RABBITMQ_PASSWORD/RABBITMQ_PORT) exactly, the
    # same names agent-runtime-service's own settings.py already uses.
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "guest"
    rabbitmq_password: str = "guest"
    rabbitmq_vhost: str = "/"
    rabbitmq_exchange: str = "memory-knowledge-events"

    event_publisher_adapter: Literal["logging", "rabbitmq"] = "logging"
    """"logging" (not "rabbitmq") stays the default: every hermetic unit test that
    boots the container without overriding this setting must not silently attempt a
    real broker connection — a service that merely logs an unpublished event on an
    unconfigured host is a safe, inert default, unlike falling back to non-durable
    storage. Mirrors agent-runtime-service's own event_publisher_adapter default
    exactly (SPEC-ARO-025's own precedent for this same field).
    """

    memory_service_name: str = "memory-knowledge-service"

    # SPEC-MK-028 12-observability: mirrors agent-runtime-service's own settings.py
    # field names/defaults exactly (`otel_exporter`/`otel_exporter_otlp_endpoint`/
    # `otel_service_name`). "console" is the safe default — genuinely functional (no
    # network calls, exports to stdout), not a stub — so hermetic tests and local runs
    # stay unaffected; "otlp" is an explicit opt-in for a real collector.
    otel_exporter: Literal["console", "otlp"] = "console"
    otel_exporter_otlp_endpoint: str = "localhost:4317"
    otel_service_name: str = "memory-knowledge-service"

    # Which EmbeddingProvider container.py wires. "deterministic" (the honest,
    # offline, no-API-key hash placeholder — see infrastructure.embedding's own
    # module docstring) stays the default so every hermetic test and offline run
    # is unaffected. "openai" wires OpenAIEmbeddingProvider (text-embedding-3-
    # small by default) and enables the real pgvector cosine-similarity path in
    # SearchMemoryService; it needs OPENAI_API_KEY. If "openai" is requested but
    # the key is missing, container.py logs a warning and falls back to
    # "deterministic" rather than refusing to boot.
    embedding_provider: Literal["deterministic", "openai"] = "deterministic"
    openai_api_key: str = ""
    openai_embedding_model: str = "text-embedding-3-small"
    openai_base_url: str = "https://api.openai.com/v1"
    # OpenAIEmbeddingProvider retries transient failures (TLS EOF / reset / read
    # timeout, and 429 / 5xx) with exponential backoff before giving up. Raise
    # these on a flaky link so a batch ingest (~5 chunk embeds per document)
    # does not fail a whole document on the first hiccup.
    openai_embedding_timeout_seconds: float = 60.0
    openai_embedding_max_attempts: int = 5

    # Browser origins allowed to call this service (support-console's admin
    # knowledge-ingest UI). Comma-separated; empty = no CORS middleware at all.
    # Mirrors tool-integration-gateway / evaluation-improvement-service's own
    # cors_allowed_origins field.
    cors_allowed_origins: str = ""

    @property
    def cors_allowed_origins_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allowed_origins.split(",") if origin.strip()]

    @property
    def sqlalchemy_url(self) -> str:
        return f"postgresql+psycopg://{self.db_username}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
