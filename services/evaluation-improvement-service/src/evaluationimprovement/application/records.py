"""Persistence/collaboration-facing records for concerns that are not domain
aggregates in their own right — mirrors memory-knowledge-service's own
application.records module.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from evaluationimprovement.domain.enums import CaseExecutionStatus, EvaluationDimension, GraderType, OutboxStatus, ScoreFailureCode
from evaluationimprovement.domain.ids import CorrelationId, IdempotencyKey


@dataclass(frozen=True, slots=True)
class OutboxRecord:
    """08-transaction-and-outbox §"Outbox 发布": "Application Transaction -> write
    aggregate -> write audit -> write outbox -> commit -> OutboxPublisher publishes ->
    mark published." Real Postgres/RabbitMQ wiring is SPEC-EI-002/EI-003 scope; this
    record shape is what both will persist and publish unchanged.
    """

    outbox_id: uuid.UUID
    event_type: str
    schema_version: int
    aggregate_id: str
    payload: str
    occurred_at: datetime
    correlation_id: CorrelationId
    status: OutboxStatus = OutboxStatus.PENDING
    attempts: int = 0
    available_at: datetime | None = None
    published_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class CommandIdempotencyRecord:
    """09-concurrency-and-idempotency §"幂等键"/"并发规则". Same key + same request_hash
    replays the cached response; same key + a different request_hash raises
    IdempotencyKeyReusedException.
    """

    idempotency_key: IdempotencyKey
    command_type: str
    target_id: str | None
    request_hash: str
    response_json: str
    created_at: datetime


@dataclass(frozen=True, slots=True)
class AuditRecordEntry:
    """11-security §"审计": dataset publish/deprecate, run create/cancel/finalize, gate
    policy change, candidate create/reject/approval-request/canary/rollback, and
    sensitive-evidence access must all write audit.
    """

    id: uuid.UUID
    action: str
    resource_type: str
    resource_id: str
    actor: str
    outcome: str
    correlation_id: str | None
    detail: str
    occurred_at: datetime


@dataclass(frozen=True, slots=True)
class ProcessedEventRecord:
    """domain-rules: "所有消费事件必须 processed-event 去重." Defined ahead of its first
    real consumer — see interfaces.event's own module docstring for why no consumer
    exists yet in SPEC-EI-001's scope.
    """

    event_id: str
    consumer_name: str
    event_type: str | None
    processed_at: datetime


@dataclass(frozen=True, slots=True)
class CaseExecutionResult:
    """The runner output ExecuteCaseService captures via AgentRuntimeEvaluationPort —
    not a domain aggregate (only EvaluationScore is a persisted evaluation fact per
    01-domain-model), just a transient record ScoreRunService's graders read.
    """

    run_id: str
    test_case_id: str
    run_generation: int
    final_state: str
    tool_calls: tuple[str, ...]
    classification: str
    policy_violation_count: int
    forbidden_tool_call_count: int
    unauthorized_memory_access_count: int
    cost_tokens: int
    latency_ms: int
    workflow_trace_ref: str
    # SPEC-EI-009: replaces the earlier `completed: bool` field, which was written on
    # every save but never actually read/branched on anywhere — a real
    # COMPLETED/FAILED/SKIPPED status finalize_scoring() (and score_case()'s own
    # eligibility guard) both now genuinely depend on. See
    # domain.enums.CaseExecutionStatus's own docstring.
    status: CaseExecutionStatus = CaseExecutionStatus.COMPLETED
    failure_reason: str | None = None


@dataclass(frozen=True, slots=True)
class GraderResult:
    """One grader's output for one (test_case, dimension) pair. ScoreRunService turns
    this into a persisted EvaluationScore.
    """

    dimension: EvaluationDimension
    score: float
    threshold: float
    grader_type: GraderType
    grader_version: str
    failure_code: ScoreFailureCode | None = None
    details: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class GatePolicyConfig:
    """05-api-contracts §"管理 API": `GET/PUT /evaluation/gates/{gatePolicy}`.
    02-business-invariants INV-EI-004: "policy_violation_count、
    forbidden_tool_call_count、unauthorized_memory_access_count 必须为 0" — the three max_*
    fields below default to that zero-tolerance floor; a caller may only tighten them
    further, never loosen past zero (EvaluateReleaseGateService enforces this).
    """

    gate_policy: str
    dimension_thresholds: dict[str, float]
    critical_case_required: bool = True
    max_policy_violations: int = 0
    max_forbidden_tool_calls: int = 0
    max_unauthorized_memory_access: int = 0


@dataclass(frozen=True, slots=True)
class ApprovalRequestRef:
    """PolicyApprovalPort's return shape — a reference into 06-policy-approval-
    governance, never a decision Evaluation Improvement makes itself.
    """

    approval_request_id: str
    status: str
