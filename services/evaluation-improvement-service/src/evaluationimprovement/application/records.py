"""Persistence/collaboration-facing records for concerns that are not domain
aggregates in their own right — mirrors memory-knowledge-service's own
application.records module.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from evaluationimprovement.domain.enums import (
    CaseExecutionStatus,
    CaseQueueStatus,
    EvaluationDimension,
    GraderType,
    OnlineSampleStatus,
    OutboxStatus,
    ScoreFailureCode,
)
from evaluationimprovement.domain.ids import CorrelationId, IdempotencyKey
from evaluationimprovement.domain.test_case import EvaluationTestCase


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
class PoisonEventRecord:
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling
    §"Poison Event": "记录 poison event 表" / "不标记 processed，除非明确 quarantine" /
    "支持 admin replay" — mirrors memory-knowledge-service's own PoisonEventRecord
    shape exactly. This domain's own event router already validates payload *shape*
    with typed Pydantic request schemas before any consume_*() service ever runs, so
    "poison" here means a payload that parsed fine at the wire but violates a domain
    invariant once inside the service — concretely,
    ConsumeApprovalDecisionEventService's own InvalidStateTransitionException/
    SelfApprovalNotAllowedException (a decision for a candidate that already moved
    past PENDING_APPROVAL through some other path, or a self-approval 06 itself
    should have refused). Never recorded in processed_events — the same event_id
    must stay replayable once whatever produced the conflicting state is fixed
    upstream (re-POSTing the same event to the same ingestion endpoint IS the
    replay; ProcessedEventRepository's own dedup check lets it through again since
    it was never marked processed).
    """

    id: uuid.UUID
    event_id: str
    consumer_name: str
    event_type: str
    payload: str
    error_message: str
    occurred_at: datetime
    recorded_at: datetime


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
    # SPEC-EI-014/015/016: four fields infrastructure.graders.deterministic's own
    # PolicyComplianceGrader/ResolutionSuccessGrader/ToolArgumentSchemaGrader and
    # infrastructure.graders.llm_judge's own AnthropicQualityJudge need, none of
    # which SPEC-EI-009's own field list carried. `approval_triggered` — did this
    # attempt actually request/obtain approval (root README design principle: "Agents
    # Must Not Self-Certify Success" applies just as much to *requesting* approval it
    # was supposed to request). `verification_passed` — did an independent
    # verification check (never the agent's own opinion) confirm
    # `test_case.verification_condition` was met. `tool_call_args` — the arguments
    # each call in `tool_calls` was actually invoked with, keyed by tool name (last
    # call wins if a tool was called more than once — same simplification
    # `tool_calls` itself already makes by being name-only, not a call log).
    # `explanation_text` — the natural-language explanation/handoff summary the agent
    # produced for the user or the next handoff agent; the one piece of free text an
    # LLM Judge actually has to read (everything else on this record is a structured
    # field a deterministic grader already covers).
    approval_triggered: bool = False
    verification_passed: bool = True
    tool_call_args: dict[str, dict[str, Any]] = field(default_factory=dict)
    explanation_text: str = ""


@dataclass(frozen=True, slots=True)
class CaseExecutionLease:
    """SPEC-EI-011: one (run, test_case) work-item CaseRunnerService/CaseRunnerWorker
    claim, execute, and retry — the queue counterpart to CaseExecutionResult's own
    outcome record. Natural key `(run_id, test_case_id)`, mirroring
    CaseExecutionResult's own docstring exactly: a case is re-leased in place, never
    appended as a new row.
    """

    run_id: str
    test_case_id: str
    run_generation: int
    status: CaseQueueStatus
    attempt_count: int
    next_attempt_at: datetime
    leased_by: str | None = None
    leased_at: datetime | None = None
    lease_expires_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class LangSmithLinkRecord:
    """SPEC-EI-013 / 10-failure-handling §"LangSmith 故障": "对离线 release gate：fail
    closed." `enabled=False` means this deployment never attempted a real LangSmith
    call at all (infrastructure.langsmith.client.LangSmithClientAdapter's own no-op
    mode) — not a failure, so EvaluateReleaseGateService must never fail a gate over
    it. `enabled=True` with `experiment_ref=None` is the genuine failure case this
    record exists to let the gate see.
    """

    run_id: str
    enabled: bool
    experiment_ref: str | None


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


@dataclass(frozen=True, slots=True)
class JudgeCalibrationCase:
    """SPEC-EI-018 (judge-calibration-drift-guard): one fixed (case, result) pair with
    a human-assigned `expected_score` — reuses EvaluationTestCase/CaseExecutionResult
    exactly as infrastructure.graders's own `grade(test_case, result)` signature
    already expects, rather than inventing a parallel shape. The calibration *set*
    itself is caller-supplied (an ops/CI concern per 14-testing-strategy, not a
    persisted 07-data-model aggregate) — only the resulting JudgeBundleStatus below is
    durable, since that is what future scoring calls must be gated on.
    """

    test_case: EvaluationTestCase
    result: CaseExecutionResult
    expected_score: float


@dataclass(frozen=True, slots=True)
class OnlineEvaluationSample:
    """SPEC-EI-028 (online-sample-evaluation) / 04-use-cases UC-EI-006: one production
    trace sampled by a policy elsewhere (which domain-owned event triggered it —
    workflow completed, ticket reopened, tool failed, approval denied — and *which*
    traces a sampling policy selects is SPEC-EI-030's own cross-domain-contract
    scope, phase-07; this record is what 07 does with a trace reference once it
    already has one). `redacted_context` is caller-pre-redacted, the same trust
    convention `EvaluationTestCase.user_request_redacted` already establishes for
    dataset test cases — 07 itself performs no redaction (11-security: "Raw ticket
    text、tool output、memory snippet 必须先脱敏再进入 dataset 或 online sample" is a
    caller-side obligation, not something this record enforces). Never a domain
    aggregate — no state machine, no invariant beyond "QUEUED until scored" — the
    same "not among the 12 named ports/aggregates" precedent
    CaseExecutionResult/LangSmithLinkRecord/JudgeBundleStatus already set.
    """

    sample_id: uuid.UUID
    candidate_id: uuid.UUID | None
    target_version: str
    source_event_type: str
    source_trace_ref: str
    redacted_context: dict[str, Any]
    status: OnlineSampleStatus
    collected_at: datetime
    scored_at: datetime | None = None
    composite_score: float | None = None
    score_details: dict[str, Any] = field(default_factory=dict)
    failure_code: ScoreFailureCode | None = None


@dataclass(frozen=True, slots=True)
class JudgeBundleStatus:
    """SPEC-EI-018 / 10-failure-handling: "Judge drift：同一 judge bundle 对固定
    calibration set 超出阈值时禁用该 bundle." Keyed by `grader_version` (a judge bundle IS
    its version string, the same identity GraderResult.grader_version already carries)
    — GraderRegistry consults this before ever invoking an LLM_JUDGE grader.
    """

    grader_version: str
    enabled: bool
    last_checked_at: datetime
    last_mean_absolute_error: float | None
    disabled_reason: str | None = None
