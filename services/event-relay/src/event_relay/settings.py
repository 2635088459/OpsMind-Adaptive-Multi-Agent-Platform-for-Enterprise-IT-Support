"""Env-driven config. Defaults are the compose-network hostnames so a bare
`python -m event_relay` inside `full-platform.yml` needs no explicit env beyond
what that file already sets for every other service.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

# The routing keys this relay bridges. Kept as a module constant (not a setting) —
# adding one means adding a transform in `routing.py` too, so it is a code change by
# definition, never a deploy-time toggle.
#
#   approval.{granted,denied,expired}.v1  -> agent-runtime (+ tool-gateway)   [SPEC-XREL-001]
#   improvement.promoted.v1              -> agent-runtime                     [SPEC-XREL-001]
#   ticket.{resolved,closed}.v1          -> memory-knowledge (learn from real resolutions)
#   workflow.{completed,failed}.v1       -> memory-knowledge (learn from agent outcomes)
BRIDGED_ROUTING_KEYS: tuple[str, ...] = (
    "approval.granted.v1",
    "approval.denied.v1",
    "approval.expired.v1",
    "improvement.promoted.v1",
    "ticket.resolved.v1",
    "ticket.closed.v1",
    "workflow.completed.v1",
    "workflow.failed.v1",
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "guest"
    rabbitmq_password: str = "guest"
    rabbitmq_vhost: str = "/"
    rabbitmq_exchange: str = "opsmind.events"
    rabbitmq_dlx: str = "opsmind.dlx"

    relay_queue: str = "event-relay.python-fanout.v1"
    relay_dlq_routing_key: str = "event-relay.python-fanout.dlq.v1"
    relay_prefetch: int = 8

    agent_runtime_base_url: str = "http://agent-runtime-service:8000"
    tool_gateway_base_url: str = "http://tool-integration-gateway:8020"
    memory_knowledge_base_url: str = "http://memory-knowledge-service:8010"

    relay_http_timeout_seconds: float = 10.0
    relay_max_attempts: int = 3
    relay_backoff_seconds: float = 2.0
    relay_requeue_delay_seconds: float = 15.0

    relay_heartbeat_file: str = "/tmp/relay-alive"
    relay_connect_retry_seconds: float = 5.0

    log_level: str = "INFO"

    @property
    def base_urls(self) -> dict[str, str]:
        return {
            "agent-runtime": self.agent_runtime_base_url,
            "tool-gateway": self.tool_gateway_base_url,
            "memory-knowledge": self.memory_knowledge_base_url,
        }


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
