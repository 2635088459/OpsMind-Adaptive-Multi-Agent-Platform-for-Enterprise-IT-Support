"""Command DTOs for every application.ports_in use case. 05-api-contracts §"API 原则":
"写 API 必须要求 authenticated actor/service identity、idempotency key 和 correlation id" —
every state-changing command below carries `actor` and `correlation_id`; commands
without a natural uniqueness key (dataset publish, candidate create, canary
operations) also carry `idempotency_key` (09-concurrency-and-idempotency §"幂等键").
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any

from evaluationimprovement.domain.enums import CandidateType, Criticality, RiskLevel
from evaluationimprovement.domain.ids import CandidateId, DatasetId, IdempotencyKey, RunId, TestCaseId


@dataclass(frozen=True, slots=True)
class TestCaseInput:
    case_key: str
    scenario: str
    user_request_redacted: str
    mock_system_state: dict[str, Any]
    ground_truth: dict[str, Any]
    allowed_tools: tuple[str, ...]
    forbidden_tools: tuple[str, ...]
    required_approval: bool
    verification_condition: dict[str, Any]
    criticality: Criticality


@dataclass(frozen=True, slots=True)
class CreateDatasetCommand:
    name: str
    version: str
    domain: str
    scenario_tags: tuple[str, ...]
    created_by: str
    actor: str
    correlation_id: str
    lineage_parent_id: DatasetId | None = None
    # SPEC-EI-008 / 11-security: caller-asserted tenant scope — see
    # domain.dataset.EvaluationDataset's own docstring.
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class AddTestCasesCommand:
    dataset_id: DatasetId
    cases: tuple[TestCaseInput, ...]
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class PublishDatasetCommand:
    dataset_id: DatasetId
    published_by: str
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class SubmitDatasetForReviewCommand:
    """SPEC-EI-006: DRAFT -> REVIEWING, a distinct auditable step from publish() —
    04-use-cases UC-EI-001 step 3: "Reviewer 检查 case 是否覆盖 ..." happens while the
    dataset sits here, before anyone calls publish().
    """

    dataset_id: DatasetId
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class RejectDatasetReviewCommand:
    dataset_id: DatasetId
    reason: str
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class DeprecateDatasetCommand:
    dataset_id: DatasetId
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class ArchiveDatasetCommand:
    dataset_id: DatasetId
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class CreateDatasetVersionCommand:
    """02-business-invariants INV-EI-005: "Dataset 发布后不可变；变更必须创建新 version，并
    保留 lineage." Seeds a brand new DRAFT dataset from an already-PUBLISHED parent —
    same name/domain, a caller-supplied new version, `lineage_parent_id` bound to the
    parent, and the parent's own test cases copied forward as the new version's
    starting point (never shared rows across dataset_id, per "版本化测试资产所有权").
    """

    parent_dataset_id: DatasetId
    new_version: str
    created_by: str
    actor: str
    correlation_id: str
    tenant_id: str = "default"


@dataclass(frozen=True, slots=True)
class CreateRunCommand:
    run_key: str
    dataset_id: DatasetId
    target_version: str
    baseline_version: str | None
    grader_bundle_version: str
    policy_version: str
    gate_policy: str
    triggered_by: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RunCiGateCommand:
    """SPEC-EI-022 (ci-evaluation-gate-harness): the same identity/binding fields
    CreateRunCommand already carries — a CI gate run *is* an EvaluationRun, driven to
    completion rather than left for admin-triggered REST calls or a standing worker.
    `baseline_version` (a target-software version string, VersionBinding metadata
    only) and `baseline_run_id` (a specific terminal PASSED run to diff against, for
    CompareRegressionCommand) are deliberately two different fields — the same
    distinction CreateRunCommand/CompareRegressionCommand already keep separate.
    """

    run_key: str
    dataset_id: DatasetId
    target_version: str
    baseline_version: str | None
    grader_bundle_version: str
    policy_version: str
    gate_policy: str
    triggered_by: str
    actor: str
    correlation_id: str
    baseline_run_id: RunId | None = None
    max_iterations: int = 5
    batch_size: int = 50


@dataclass(frozen=True, slots=True)
class CancelRunCommand:
    run_id: RunId
    reason: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ExecuteCaseCommand:
    run_id: RunId
    test_case_id: TestCaseId
    attempt: int
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class SkipCaseCommand:
    """SPEC-EI-009 / 10-failure-handling §"Partial Run": explicitly marks a case as
    never-to-be-executed for this run (the "未执行 case" a Partial report must list),
    rather than leaving it permanently unaccounted-for.
    """

    run_id: RunId
    test_case_id: TestCaseId
    reason: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ScoreCaseCommand:
    """ScoreRunService grades every dimension a case's own graders cover for one
    already-executed case; `run_generation` guards against a stale runner reply
    (09-concurrency-and-idempotency §"Stale 结果").
    """

    run_id: RunId
    test_case_id: TestCaseId
    run_generation: int
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class FinalizeRunScoringCommand:
    """Marks a run's scoring phase complete once every expected case has a score or is
    explicitly skipped/failed (08-transaction-and-outbox §"Run 完成事务").
    """

    run_id: RunId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CompareRegressionCommand:
    run_id: RunId
    baseline_run_id: RunId | None
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class EvaluateReleaseGateCommand:
    run_id: RunId
    gate_policy: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CreateImprovementCandidateCommand:
    candidate_type: CandidateType
    source_run_id: RunId
    source_failure_cluster_id: str | None
    target_component: str
    proposed_change: dict[str, Any]
    risk_level: RiskLevel
    created_by: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class RecordCandidateBenchmarkCommand:
    """SPEC-EI-025 (candidate-benchmark-binding-gate-enforcement): `benchmark_run_id`
    replaces a bare caller-supplied `passed: bool` — CreateImprovementCandidateService
    derives pass/fail from that run's own terminal PASSED/FAILED release-gate status,
    never trusts a claim with no evidence behind it.
    """

    candidate_id: CandidateId
    benchmark_run_id: RunId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RequestCandidateApprovalCommand:
    candidate_id: CandidateId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ApproveCandidateCommand:
    candidate_id: CandidateId
    approved_by: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RejectCandidateCommand:
    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class CanaryStageInput:
    traffic_percent: float
    min_duration_minutes: int
    rollback_error_rate_threshold: float
    sample_size: int = 1


@dataclass(frozen=True, slots=True)
class StartCanaryCommand:
    candidate_id: CandidateId
    plan_version: str
    stages: tuple[CanaryStageInput, ...]
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class AdvanceCanaryCommand:
    candidate_id: CandidateId
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class PauseCanaryCommand:
    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class RequestCanaryRollbackCommand:
    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class CompleteCanaryRollbackCommand:
    candidate_id: CandidateId
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class PromoteCandidateCommand:
    candidate_id: CandidateId
    promoted_version: str
    actor: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class RollbackPromotedCandidateCommand:
    """SPEC-EI-036 (evaluation-contract-e2e-harness-final-release): closes a real gap
    the phase's own final coverage audit found — `RequestCanaryRollbackCommand`'s own
    application-layer handler only ever accepts canary_status
    ACTIVE/EXPANDING/PAUSED/FAILED, so a candidate that already reached PROMOTED
    (canary_status=SUCCEEDED, terminal — no outgoing Canary sub-state transition)
    could never be rolled back through the application layer at all, even though
    01-domain-model's own state machine explicitly allows PROMOTED -> ROLLED_BACK
    (ImprovementCandidate.rollback() sets it unconditionally, bypassing the Canary
    sub-state machine entirely — a promoted release has no more canary traffic
    percentage to track through). domain-rules "07 只请求 rollback，由 Runtime/Config
    owner 执行" still applies: this command requests the rollback (and reflects the
    top-level state immediately, since a promoted release is binary — either it is
    live or it has been rolled back, unlike an in-progress canary's own multi-stage
    journey), the same way RequestCanaryRollbackCommand never itself flips traffic.
    """

    candidate_id: CandidateId
    reason: str
    actor: str
    correlation_id: str
    idempotency_key: IdempotencyKey


@dataclass(frozen=True, slots=True)
class CollectOnlineSampleCommand:
    """SPEC-EI-028 (online-sample-evaluation) / 04-use-cases UC-EI-006 steps 1-3:
    "07 消费...事件，根据 sampling policy 选择 trace，脱敏后写入 online evaluation queue." Which
    upstream event triggered this sample and the sampling policy that selected it are
    both SPEC-EI-030's own cross-domain-contract scope (phase-07) — this command is
    the boundary 07 owns: it accepts an already-selected, already-redacted trace
    reference and takes it from there. `redacted_context` is caller-pre-redacted, the
    same trust convention `AddTestCasesCommand`'s own `TestCaseInput.
    user_request_redacted` already establishes.
    """

    candidate_id: CandidateId | None
    target_version: str
    source_event_type: str
    source_trace_ref: str
    redacted_context: dict[str, Any]
    actor: str
    correlation_id: str


# SPEC-EI-030 (ticket-runtime-evaluation-contract) / SPEC-EI-031 (memory-tool-
# evidence-contract): consumed cross-domain events, field names transcribed from each
# real producer's own actual published payload (never this domain's own illustrative
# 06-event-contracts sketch, which several of these diverge from) — the same "02
# remains system of record" precedent memory-knowledge-service's own
# ConsumeTicketResolvedCommand/ConsumeWorkflowCompletedCommand docstrings establish.
# Every command carries `event_id` (processed-event dedup key) and `correlation_id`
# (05-api-contracts §"API 原则" applies to consumed events too, not just commands a
# caller issues directly).
@dataclass(frozen=True, slots=True)
class ConsumeTicketResolvedCommand:
    """SPEC-EI-030: consumed `ticket.resolved.v1` (02-ticket-workflow's own
    TicketResolvedEventMapper — supportQueueId/assigneeId/resolutionCycleId/
    previousStatus/newStatus/resolutionCode/resolutionSummary/resolvedBy/resolvedAt/
    autoCloseDueAt, `dataClassification="INTERNAL"` on the envelope itself).
    `resolution_summary` is free text 07 must redact before it ever reaches an
    online sample (11-security) — carried here only so the consumer can decide
    whether to redact it away entirely, never forwarded verbatim.
    """

    event_id: str
    ticket_id: str
    resolution_code: str
    resolution_summary: str
    resolved_at: datetime
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeTicketReopenedCommand:
    """SPEC-EI-030: consumed `ticket.reopened.v1` (TicketReopenedEventMapper —
    supportQueueId/assigneeId/previousResolutionCycleId/newResolutionCycleId/
    previousStatus/newStatus/reopenReasonCode/reopenCount/reopenedBy/reopenedAt/
    ownershipStatus). UC-EI-006 names `ticket reopened` explicitly as one of the
    signals online sampling reacts to — a reopen is itself evidence a prior
    resolution's quality may have been poor.
    """

    event_id: str
    ticket_id: str
    reopen_reason_code: str
    reopen_count: int
    reopened_at: datetime
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeWorkflowCompletedCommand:
    """SPEC-EI-030: consumed `workflow.completed.v1` (03-agent-runtime-orchestration's
    own real published payload — CompleteWorkflowService._to_payload:
    workflowInstanceId/fromState/toState/workflowVersion/occurredAt; the envelope
    itself carries ticketId, no ticketCycleId — that service's own OutboxRecord never
    carries one).
    """

    event_id: str
    workflow_instance_id: str
    ticket_id: str
    to_state: str
    workflow_version: int
    occurred_at: datetime
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeWorkflowFailedCommand:
    """SPEC-EI-030: consumed `workflow.failed.v1` (FailWorkflowService's own real
    payload: adds failureReason to workflow.completed.v1's own shape).
    """

    event_id: str
    workflow_instance_id: str
    ticket_id: str
    to_state: str
    workflow_version: int
    failure_reason: str
    occurred_at: datetime
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeToolCompletedCommand:
    """SPEC-EI-031 (memory-tool-evidence-contract): consumed `tool.completed.v1`
    (05-tool-integration-gateway's own real published payload —
    outbox_events.build_success_completed_event/build_terminal_failed_completed_event
    — one event type covers both outcomes, distinguished by `status`: COMPLETED/
    TERMINAL_FAILED/UNCERTAIN/POLICY_DENIED/APPROVAL_DENIED/CANCELLED). `summary`
    (free text) and `structured_output` (may embed raw tool output) are 07's own
    redaction responsibility — never forwarded verbatim; `redaction_status` is 05's
    own evidence-redaction marker (`envelope.redaction_status.name`) this consumer
    honors: a not-yet-redacted envelope is dropped from the sample entirely rather
    than redacted here a second, different way (11-security: single source of truth
    for what "redacted" means for one piece of evidence).
    """

    event_id: str
    tool_request_id: str
    capability_name: str
    status: str
    redaction_status: str | None
    error_code: str | None
    occurred_at: datetime
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeMemoryRetrievalCompletedCommand:
    """SPEC-EI-031: consumed `memory.retrieval.completed.v1` — 04-memory-knowledge has
    no real publisher for this event anywhere in this repo yet (only named on 07's own
    illustrative 06-event-contracts sketch), the same "the real contract this consumer
    is ready for, even though nothing upstream sends it yet" honest-placeholder
    precedent HttpAgentRuntimeEvaluationAdapter's own docstring already established
    for 03's evaluation endpoint. Field names are 07's own best-effort shape (query
    context, result count, ACL-scope outcome) pending a real event to transcribe
    field-for-field from, same limitation this command's own docstring names.
    """

    event_id: str
    query_id: str
    memory_type: str
    result_count: int
    acl_scope_denied: bool
    occurred_at: datetime
    correlation_id: str


# SPEC-EI-032 (policy-approval-release-approval-contract): consumed
# `approval.granted.v1`/`approval.denied.v1` (06-policy-approval-governance's own real
# published payload — ApprovalGrantedEvent.from()/ApprovalDeniedEvent.from():
# approvalRequestId/requestKey/sourceDomain/sourceRequestId/requestHash/decidedBy/
# reason/conditions[/separationOfDutiesCheck on grant only]). `source_domain`/
# `source_request_id` are how this consumer recognizes "this decision is for one of
# *my own* candidates" — the exact two fields HttpPolicyApprovalAdapter's own request
# payload already sends as `sourceDomain: "evaluation-improvement"`/
# `sourceRequestId: str(candidateId)` (SPEC-EI-026), closing the request/consume loop
# that spec's own traceability entry deferred to this one.
@dataclass(frozen=True, slots=True)
class ConsumeApprovalGrantedCommand:
    event_id: str
    approval_request_id: str
    source_domain: str
    source_request_id: str
    decided_by: str
    correlation_id: str


@dataclass(frozen=True, slots=True)
class ConsumeApprovalDeniedCommand:
    event_id: str
    approval_request_id: str
    source_domain: str
    source_request_id: str
    decided_by: str
    reason: str
    correlation_id: str
