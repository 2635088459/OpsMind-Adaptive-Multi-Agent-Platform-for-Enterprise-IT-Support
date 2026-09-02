"""SPEC-TG-002 widens SPEC-TG-001's in-memory-only settings with the real
Postgres connection fields; SPEC-TG-003 adds the RabbitMQ fields. Mirrors the
sibling ticket-workflow-service's/agent-runtime-service's/memory-knowledge-
service's own env var names and local-dev defaults exactly
(``DB_HOST``/``DB_PORT``/``DB_NAME``/``DB_USERNAME``/``DB_PASSWORD``, matching
``infrastructure/docker-compose/local-platform.yml``'s single shared Postgres
container) — Tool Gateway tables live in their own ``tool`` schema inside that
same database (07-data-model §"Database"), not a separate database.
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

    # SPEC-TG-001's in-memory adapters remain available (fast, hermetic unit
    # tests use them directly, bypassing tool_gateway.container entirely, or
    # explicitly override this to "memory" — see tests/conftest.py). "postgres"
    # is the default here for the same reason memory-knowledge-service's own
    # memory_persistence defaults to "postgres": a schema-baseline service that
    # silently falls back to non-durable storage in production is a worse
    # failure mode than refusing to boot without a reachable database.
    tool_gateway_persistence: Literal["memory", "postgres"] = "postgres"

    # SPEC-TG-003 08-transaction-and-outbox §"Outbox Publisher": mirrors
    # infrastructure/docker-compose/local-platform.yml's opsmind-rabbitmq env
    # var names/defaults exactly, the same names memory-knowledge-service's and
    # agent-runtime-service's own settings.py already use.
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "guest"
    rabbitmq_password: str = "guest"
    rabbitmq_vhost: str = "/"
    rabbitmq_exchange: str = "tool-integration-gateway-events"

    event_publisher_adapter: Literal["logging", "rabbitmq"] = "logging"
    """"logging" (not "rabbitmq") stays the default: every hermetic unit test
    that boots the container without overriding this setting must not silently
    attempt a real broker connection — a service that merely logs an
    unpublished event on an unconfigured host is a safe, inert default, unlike
    falling back to non-durable storage. Mirrors memory-knowledge-service's own
    event_publisher_adapter default exactly.
    """

    tool_gateway_service_name: str = "tool-integration-gateway"

    # SPEC-TG-026 12-observability: mirrors memory-knowledge-service's/agent-
    # runtime-service's own field names/defaults exactly. "console" is the
    # safe default — genuinely functional (exports real metrics/spans to
    # stdout), not a stub, so nothing about running this service locally or
    # under pytest depends on a collector being reachable.
    otel_exporter: Literal["console", "otlp"] = "console"
    otel_exporter_otlp_endpoint: str = "localhost:4317"
    otel_service_name: str = "tool-integration-gateway"

    # SPEC-TG-030 10-failure-handling §"Connector Crash Or Unavailability":
    # "Consecutive failures move an ACTIVE connector to DEGRADED... Health
    # check failures beyond threshold move it to DISABLED." Two distinct
    # thresholds compared against the same running consecutive-failure
    # counter (see domain.connector.ToolConnector.record_health_check_failure)
    # — disable_after must stay greater than degrade_after or a connector
    # would jump straight past DEGRADED.
    connector_degrade_after_failures: int = 3
    connector_disable_after_failures: int = 5

    # SPEC-SC-018/020 follow-up: support-console (domain 10) is this service's first
    # browser caller — empty/deny by default, mirrors evaluation-improvement-
    # service's own cors_allowed_origins field exactly. Deliberately GET-only, and
    # deliberately never includes X-Caller-Id/X-Caller-Type in the allowed request
    # headers (see main.py's own CORSMiddleware config) — a cross-origin page can
    # therefore never complete a write call here (POST isn't an allowed method at
    # all) nor spoof a SERVICE-caller identity through a real browser (the header a
    # forged caller-type would need is never let through CORS preflight).
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
