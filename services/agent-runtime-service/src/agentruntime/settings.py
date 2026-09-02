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

    # SPEC-ARO-043 (phase-10 Conversational Intake): a real Keycloak client_credentials
    # service identity for this service's own outbound calls to 02-ticket-workflow/
    # 06-policy-approval-governance — structurally the same kind of client the
    # 2026-09-01 integration verification's own "integration-test-client" was, but a
    # real, production-grade identity owned by this service. "disabled" (not a live
    # Keycloak URL) is the safe default for hermetic tests and local runs that never
    # exercise SPEC-ARO-038/040/041's outbound calls, mirroring event_publisher_adapter's
    # own "logging"-by-default reasoning — a caller that actually needs a token gets a
    # clear OutboundAuthenticationException, never a silent unauthenticated call.
    keycloak_token_url: str = "disabled"
    agent_runtime_service_client_id: str = "agent-runtime-service"
    agent_runtime_service_client_secret: str = ""
    # SPEC-ARO-043 domain-rules: "the client secret is never committed to source —
    # environment-injected only" — this field's default is deliberately blank, never a
    # real-looking placeholder secret.

    # SPEC-ARO-038 (phase-10): the real 02-ticket-workflow base URL this service's own
    # outbound HTTP client calls POST /api/v1/tickets against. 8080 is Spring Boot's own
    # unconfigured default (ticket-workflow-service's application.yml sets no
    # server.port) — no service-container port mapping exists yet in this repo's own
    # local-platform docker-compose (it wires shared infra only, not the services
    # themselves), so this is a local-dev convenience default, override via env in any
    # real multi-service deployment.
    ticket_workflow_base_url: str = "http://localhost:8080"

    # SPEC-ARO-039 (phase-10): the real 04-memory-knowledge base URL this service's own
    # outbound HTTP client calls POST /internal/memory/v1/search against. 8010 is that
    # service's own real uvicorn default (memoryknowledge.main.run()).
    memory_knowledge_base_url: str = "http://localhost:8010"

    # SPEC-ARO-039's own multimodal follow-up: the real attachment-service base URL
    # HttpAttachmentClient calls GET /api/v1/attachments/{ref}/content against. 8090 is
    # that service's own real Spring Boot default (application.yml sets no server.port
    # override there either, but its own local-platform docker-compose wiring maps it
    # to 8090 — see full-platform.yml's own attachment-service block).
    attachment_service_base_url: str = "http://localhost:8090"

    # SPEC-ARO-041 (phase-10): a real categoryId/supportQueueId from 02-ticket-workflow's
    # own reference-data catalog — no seed data for either exists anywhere in this
    # platform yet (confirmed by reading ticket-workflow-service's own migrations
    # directly), so this service cannot safely invent one. Blank (unconfigured) is the
    # safe default; SendMessageService fails closed with
    # EscalationRoutingNotConfiguredException rather than fabricate an id that may not
    # exist. escalation_default_team_name is a human-readable label for that one
    # configured queue (the real triage response carries no team name of its own to
    # reuse — confirmed by reading TriageTicketResponse directly), operator-supplied,
    # never fabricated by this service.
    escalation_default_category_id: str = ""
    escalation_default_support_queue_id: str = ""
    escalation_default_priority: str = "MEDIUM"
    escalation_default_team_name: str = ""

    # SPEC-ARO-040 (phase-10): the real 06-policy-approval-governance base URL this
    # service's own outbound HTTP client calls POST /api/v1/approval-requests against.
    # 8080 is Spring Boot's own unconfigured default (same caveat as
    # ticket_workflow_base_url's own comment — no per-service port mapping exists yet
    # in this repo's own local-platform docker-compose).
    policy_approval_governance_base_url: str = "http://localhost:8080"

    # SPEC-ARO-040 domain-rules: "the bounded wait's timeout is a configurable value,
    # not hardcoded, with its real default determined during phase implementation via
    # load testing — never an indefinite block." These conservative defaults are a
    # starting point, not a load-tested figure — no real tool executor exists anywhere
    # in this platform yet to time against (see ActionConfirmationService's own
    # docstring), so there is nothing to load-test against today.
    confirm_bounded_wait_timeout_seconds: float = 2.0
    confirm_bounded_wait_poll_interval_seconds: float = 0.1

    # domain 09 (employee-portal)'s own frontend calls conversation_router's endpoints
    # directly from a genuinely different browser origin (its own Vite dev server, no
    # reverse proxy in front of either side yet) — without CORS this browser call is
    # blocked outright, no response ever reaches the page's own JS. Comma-separated,
    # empty/deny-by-default like every other cross-cutting default in this service;
    # an operator opts specific frontend origins in. Unlike user-access-authentication-
    # service's own BFF cookie relay, this endpoint is Bearer-token-authenticated, not
    # cookie-based, so `allow_credentials` stays False — no cookie ever needs to cross
    # this boundary.
    cors_allowed_origins: str = ""

    # SPEC-ARO-039 follow-up: "static" (default) keeps StaticConversationReasoningAdapter
    # — every hermetic test in this service relies on it, the same reason
    # evaluation-improvement-service's own llm_judge_mode/langsmith_mode both default
    # away from a real network call. "anthropic"/"openai" each wire their own real
    # adapter — requires that provider's own api key, and only takes effect if that
    # provider's own package actually imports and its client constructs successfully
    # (see container.py's own `_build_conversation_reasoning_port()`). Both model
    # defaults are deliberately faster/cheaper than evaluation-improvement-service's
    # own default judge model: this call runs synchronously inside a human's own
    # inline chat request (SendMessageService), where latency directly affects the
    # employee waiting on a reply, unlike that service's own offline batch-grading
    # use case.
    conversation_reasoning_mode: Literal["static", "anthropic", "openai"] = "static"
    anthropic_api_key: str | None = None
    conversation_reasoning_anthropic_model: str = "claude-sonnet-5"
    openai_api_key: str | None = None
    conversation_reasoning_openai_model: str = "gpt-5-mini"

    @property
    def cors_allowed_origins_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allowed_origins.split(",") if origin.strip()]

    @property
    def sqlalchemy_url(self) -> str:
        return f"postgresql+psycopg://{self.db_username}:{self.db_password}@{self.db_host}:{self.db_port}/{self.db_name}"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
