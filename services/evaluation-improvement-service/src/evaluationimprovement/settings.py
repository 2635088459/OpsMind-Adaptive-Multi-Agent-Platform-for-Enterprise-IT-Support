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

    # SPEC-EI-012: "fake" (default) keeps FakeAgentRuntimeEvaluationAdapter — the
    # deterministic in-process simulator every hermetic test in this service relies
    # on. "http" wires HttpAgentRuntimeEvaluationAdapter, the real client against
    # 03-agent-runtime-orchestration's own evaluation endpoint contract.
    agent_runtime_evaluation_mode: Literal["fake", "http"] = "fake"
    agent_runtime_base_url: str = "http://localhost:8003"
    agent_runtime_timeout_seconds: float = 60.0

    # SPEC-EI-013: "noop" (default) keeps NoOpLangSmithExperimentAdapter —
    # LangSmithPort.is_enabled() reports False, so EvaluateReleaseGateService's own
    # fail-closed rule never triggers over it (10-failure-handling §"LangSmith 故障"
    # applies only to a genuinely *attempted* call). "sdk" wires
    # SdkLangSmithExperimentAdapter against the real langsmith SDK — requires
    # langsmith_api_key, and only takes effect if the `langsmith` package actually
    # imports (see container.py's own `_build_langsmith_port()`).
    langsmith_mode: Literal["noop", "sdk"] = "noop"
    langsmith_api_key: str | None = None
    langsmith_api_url: str = "https://api.smith.langchain.com"

    # SPEC-EI-011: how long a claimed case-execution lease stays owned before another
    # worker's reclaim_expired_leases() pass may take it back — long enough to outlast
    # a slow-but-healthy Agent Runtime call, short enough that a genuinely crashed
    # worker's work is not stuck for long.
    case_runner_lease_seconds: int = 300

    # SPEC-EI-016: "placeholder" (default) keeps ExplanationQualityJudge — always
    # UNSCORED, no network call, no key required (every hermetic test relies on this).
    # "anthropic" wires AnthropicQualityJudge against the real `anthropic` SDK —
    # 02-business-invariants INV-EI-003 still applies regardless: this dimension is
    # quality-only and never read by any gate/regression decision.
    llm_judge_mode: Literal["placeholder", "anthropic"] = "placeholder"
    anthropic_api_key: str | None = None
    anthropic_judge_model: str = "claude-opus-5"

    # SPEC-EI-026: "fake" (default) keeps FakePolicyApprovalAdapter — every request
    # comes back PENDING, no network call, no key required (every hermetic test in
    # this service relies on this). "http" wires HttpPolicyApprovalAdapter, the real
    # client against 06-policy-approval-governance's own
    # `POST /api/v1/approval-requests` contract. `policy_approval_service_token` is
    # this service's own bearer token for that call — 06's own
    # GovernanceRequestContext requires a real Spring Security Authentication with a
    # non-blank name, which no cross-service identity mechanism in this repo issues
    # yet; carrying the setting now (even unset) is this spec's own honest half of
    # that contract, the same precedent HttpAgentRuntimeEvaluationAdapter set for a
    # 03 endpoint that does not exist yet either.
    policy_approval_mode: Literal["fake", "http"] = "fake"
    policy_approval_base_url: str = "http://localhost:8006"
    policy_approval_timeout_seconds: float = 30.0
    policy_approval_service_token: str | None = None

    # SPEC-SC-015: support-console (domain 10) is the first browser caller this
    # service has ever had — every other consumer so far (agent-runtime-service,
    # policy-approval-governance-service) is a server-to-server client, so no
    # CORS middleware existed at all until now. Empty/deny by default (the same
    # INV-UA-002-style default every other service's own CORS config in this
    # platform uses), comma-separated real origins opted in per environment.
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
