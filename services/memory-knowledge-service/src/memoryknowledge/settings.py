"""SPEC-MK-001: service configuration. Only in-memory adapters exist at this spec's
scope — memory_persistence and event_publisher_adapter are each a single-value Literal
today so the setting's *shape* is already in place for SPEC-MK-002 (schema baseline,
adds "postgres" to memory_persistence and the real `memory` Postgres schema) and
SPEC-MK-003 (outbox/idempotency/audit baseline, adds "rabbitmq" to
event_publisher_adapter) to extend without a breaking rename — mirrors
agent-runtime-service's own settings.py evolution across its own SPEC-ARO-001/002/003.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    memory_persistence: Literal["memory"] = "memory"
    """SPEC-MK-002 will add "postgres" here alongside the real `memory` Postgres schema
    adapters (07-data-model) — until then, this is the only backend that exists.
    """

    event_publisher_adapter: Literal["logging"] = "logging"
    """SPEC-MK-003 will add "rabbitmq" here alongside the real broker adapter
    (08-transaction-and-outbox). "logging" is a safe, inert default for hermetic tests
    and local runs — mirrors agent-runtime-service's own event_publisher_adapter default.
    """

    memory_service_name: str = "memory-knowledge-service"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
