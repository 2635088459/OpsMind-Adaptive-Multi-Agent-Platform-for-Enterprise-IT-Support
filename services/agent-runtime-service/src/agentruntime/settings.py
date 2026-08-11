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

    @property
    def sqlalchemy_url(self) -> str:
        return f"postgresql+psycopg://{self.db_username}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
