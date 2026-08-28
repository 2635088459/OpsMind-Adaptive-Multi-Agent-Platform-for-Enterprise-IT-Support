"""Output ports (13-package-and-class-design §"端口": DatasetRepository,
EvaluationRunRepository, ScoreRepository, RegressionReportRepository,
ImprovementCandidateRepository, OutboxRepository, ProcessedEventRepository,
LangSmithPort, AgentRuntimeEvaluationPort, PolicyApprovalPort, TelemetryArtifactPort,
ClockPort, AuthorizationPort — plus TestCaseRepository, CommandIdempotencyRepository,
AuditRecordRepository, GatePolicyRepository, GraderRegistryPort and EventPublisherPort,
added the same way memory-knowledge-service's own SPEC-MK-001 extended its LLD-listed
port set). Structural typing.Protocol — infrastructure adapters satisfy these by
shape, never by inheritance.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Protocol

from evaluationimprovement.application.records import (
    ApprovalRequestRef,
    AuditRecordEntry,
    CaseExecutionResult,
    CommandIdempotencyRecord,
    GatePolicyConfig,
    GraderResult,
    OutboxRecord,
    ProcessedEventRecord,
)
from evaluationimprovement.domain.dataset import EvaluationDataset
from evaluationimprovement.domain.enums import CandidateStatus, DatasetStatus, EvaluationDimension, GraderType, RunStatus
from evaluationimprovement.domain.evaluation_run import EvaluationRun
from evaluationimprovement.domain.ids import CandidateId, DatasetId, IdempotencyKey, ReportId, RunId, TestCaseId
from evaluationimprovement.domain.improvement_candidate import ImprovementCandidate
from evaluationimprovement.domain.regression_report import RegressionReport
from evaluationimprovement.domain.score import EvaluationScore
from evaluationimprovement.domain.test_case import EvaluationTestCase


class ClockPort(Protocol):
    def now(self) -> datetime: ...


class DatasetRepository(Protocol):
    def find_by_id(self, dataset_id: DatasetId) -> EvaluationDataset | None: ...

    def find_by_name_version(self, name: str, version: str) -> EvaluationDataset | None:
        """07-data-model `evaluation_datasets` §"唯一键": `(name, version)`."""
        ...

    def save(self, dataset: EvaluationDataset, expected_status: DatasetStatus | None) -> EvaluationDataset:
        """expected_status=None inserts a brand new dataset. Otherwise replaces an
        existing one under a compare-and-swap on its current status.
        """
        ...

    def list_published(self, domain: str | None, tenant_id: str, limit: int) -> list[EvaluationDataset]: ...

    def find_versions(self, name: str, tenant_id: str) -> list[EvaluationDataset]:
        """SPEC-EI-004: every version of one dataset name, any status, oldest first —
        the lineage chain `lineage_parent_id` implies but that no other query surface
        exposes. SPEC-EI-008: scoped to the caller's own tenant, same as
        list_published — never leaks another tenant's lineage.
        """
        ...


class TestCaseRepository(Protocol):
    def find_by_id(self, test_case_id: TestCaseId) -> EvaluationTestCase | None: ...

    def find_by_dataset(self, dataset_id: DatasetId) -> list[EvaluationTestCase]: ...

    def find_by_natural_key(self, dataset_id: DatasetId, case_key: str) -> EvaluationTestCase | None:
        """07-data-model `evaluation_test_cases` §"唯一键": `(dataset_id, case_key)`."""
        ...

    def save_many(self, cases: tuple[EvaluationTestCase, ...]) -> None: ...


class EvaluationRunRepository(Protocol):
    def find_by_id(self, run_id: RunId) -> EvaluationRun | None: ...

    def find_by_run_key(self, run_key: str) -> EvaluationRun | None:
        """07-data-model `evaluation_runs` §"唯一键": `run_key`. 09-concurrency-and-
        idempotency §"并发规则": "同一个 runKey 重复提交必须返回同一 run."
        """
        ...

    def save(self, run: EvaluationRun, expected_status: RunStatus | None) -> EvaluationRun:
        """expected_status=None inserts a brand new run. Otherwise replaces an existing
        one under a compare-and-swap on its current status.
        """
        ...

    def current_generation(self, run_id: RunId) -> int:
        """09-concurrency-and-idempotency §"Stale 结果": bumped whenever a run restarts
        case execution (e.g. after a partial-run retry); ExecuteCaseService/
        ScoreRunService compare a case result's own generation against this.
        """
        ...

    def find_by_dataset(self, dataset_id: DatasetId, status: RunStatus | None, limit: int) -> list[EvaluationRun]:
        """SPEC-EI-010 / 05-api-contracts: "状态可见性" — every run against a dataset,
        newest first, optionally narrowed to one status.
        """
        ...


class ScoreRepository(Protocol):
    def save(self, score: EvaluationScore) -> EvaluationScore:
        """02-business-invariants INV-EI-007: append-only. If an active score already
        exists for (run_id, test_case_id, dimension, grader_version), the repository
        marks it superseded (EvaluationScore.superseded()) before inserting the new
        one — never an in-place UPDATE of the historical row.
        """
        ...

    def find_active_by_run(self, run_id: RunId) -> list[EvaluationScore]: ...

    def find_active(self, run_id: RunId, test_case_id: TestCaseId, dimension: EvaluationDimension) -> EvaluationScore | None: ...

    def count_distinct_scored_cases(self, run_id: RunId) -> int:
        """08-transaction-and-outbox §"Run 完成事务": used to check every expected case
        has at least one recorded score before a run leaves SCORING.
        """
        ...


class CaseExecutionResultRepository(Protocol):
    """Not among the 12 named ports (13-package-and-class-design), added the same way
    CommandIdempotencyRepository was — the handoff point between ExecuteCaseService
    (writes) and ScoreRunService (reads) for the same executed case, since neither
    a domain aggregate nor any of the 12 named repositories owns this transient
    runner output (only EvaluationScore is a persisted evaluation fact per
    01-domain-model).
    """

    def save(self, result: CaseExecutionResult) -> None: ...

    def find(self, run_id: RunId, test_case_id: TestCaseId) -> CaseExecutionResult | None: ...

    def find_by_run(self, run_id: RunId) -> list[CaseExecutionResult]: ...


class RegressionReportRepository(Protocol):
    def save(self, report: RegressionReport) -> RegressionReport: ...

    def find_by_id(self, report_id: ReportId) -> RegressionReport | None: ...

    def find_by_run(self, run_id: RunId) -> RegressionReport | None: ...


class ImprovementCandidateRepository(Protocol):
    def find_by_id(self, candidate_id: CandidateId) -> ImprovementCandidate | None: ...

    def save(self, candidate: ImprovementCandidate, expected_status: CandidateStatus | None) -> ImprovementCandidate:
        """expected_status=None inserts a brand new candidate. Otherwise replaces an
        existing one under a compare-and-swap on its current status (09-concurrency-
        and-idempotency §"并发规则": "Candidate promotion 使用 optimistic locking").
        """
        ...

    def find_by_natural_key(
        self, source_run_id: RunId, source_failure_cluster_id: str | None, target_component: str
    ) -> ImprovementCandidate | None:
        """09-concurrency-and-idempotency §"幂等键": `sourceRunId:failureClusterId:
        targetComponent`.
        """
        ...


class OutboxRepository(Protocol):
    """domain-rules: "所有状态迁移必须同事务写 audit/outbox." Real Postgres/RabbitMQ wiring
    is SPEC-EI-002/EI-003 scope.
    """

    def append(self, record: OutboxRecord) -> None: ...

    def find_dispatchable(self, now: datetime, limit: int) -> list[OutboxRecord]: ...

    def mark_published(self, outbox_id: uuid.UUID, published_at: datetime) -> None: ...

    def mark_failed(self, outbox_id: uuid.UUID, next_available_at: datetime, attempts: int) -> None: ...

    def mark_dead_letter(self, outbox_id: uuid.UUID) -> None: ...

    def find_dead_letter(self, limit: int) -> list[OutboxRecord]: ...


class ProcessedEventRepository(Protocol):
    """domain-rules: "所有消费事件必须 processed-event 去重." Defined ahead of its first
    real consumer — see interfaces.event's own module docstring.
    """

    def is_processed(self, event_id: str, consumer_name: str) -> bool: ...

    def mark_processed(self, record: ProcessedEventRecord) -> None: ...


class CommandIdempotencyRepository(Protocol):
    def find_by_key(self, idempotency_key: IdempotencyKey) -> CommandIdempotencyRecord | None: ...

    def save(self, record: CommandIdempotencyRecord) -> CommandIdempotencyRecord: ...


class AuditRecordRepository(Protocol):
    def append(self, entry: AuditRecordEntry) -> None: ...

    def find_recent(self, limit: int) -> list[AuditRecordEntry]: ...


class GatePolicyRepository(Protocol):
    """05-api-contracts §"管理 API": `GET/PUT /evaluation/gates/{gatePolicy}`."""

    def find_by_name(self, gate_policy: str) -> GatePolicyConfig | None: ...

    def save(self, config: GatePolicyConfig) -> GatePolicyConfig: ...


class LangSmithPort(Protocol):
    """13-package-and-class-design `infrastructure/langsmith/`. Real dataset/
    experiment mapping logic is SPEC-EI-013 (langsmith-experiment-linkage) scope;
    SPEC-EI-001 defines the port and a no-op adapter so CreateRunService/
    ScoreRunService have a stable seam to call.
    """

    def link_experiment(self, run_id: RunId, dataset_name: str, dataset_version: str) -> str | None:
        """Returns an experiment reference (opaque string), or None if LangSmith is
        unavailable. 10-failure-handling §"LangSmith 故障": "对离线 release gate：fail
        closed" is enforced by the *caller*, not this port — this port only ever
        reports availability, it never decides gate outcomes.
        """
        ...


class AgentRuntimeEvaluationPort(Protocol):
    """13-package-and-class-design `infrastructure/runtime/agent_runtime_client.py`.
    Real HTTP integration with 03-agent-runtime-orchestration's own evaluation
    endpoint is SPEC-EI-012 (agent-runtime-evaluation-client-contract) scope.
    Deliberately read-only/execute-in-mock-state shaped — see the domain-rules
    "forbidden: direct_ticket_state_write / direct_workflow_state_write /
    direct_tool_execution" list this port's own single method must never violate.
    """

    def execute_case(self, run_id: RunId, target_version: str, test_case: EvaluationTestCase, run_generation: int) -> CaseExecutionResult: ...


class PolicyApprovalPort(Protocol):
    """13-package-and-class-design `infrastructure/policy/` (added the same way
    AgentRuntimeEvaluationPort's own `infrastructure/runtime/` seam was). Real
    integration with 06-policy-approval-governance is SPEC-EI-026/032 scope.
    domain-rules "forbidden: policy_approval_ownership_bypass" — this port only ever
    requests/reads an approval decision, never grants one itself.
    """

    def request_approval(self, candidate_id: CandidateId, target_component: str, risk_level: str, requested_by: str) -> ApprovalRequestRef: ...


class TelemetryArtifactPort(Protocol):
    """07-data-model §"Artifact 引用": stores `artifact_provider`/`artifact_uri`/
    `artifact_hash`/`retention_until` references, never the underlying large payload.
    """

    def store_reference(self, provider: str, uri: str, content_hash: str, retention_until: str | None) -> str:
        """Returns an opaque artifact reference id."""
        ...


class AuthorizationPort(Protocol):
    """11-security §"身份与权限": EVALUATION_VIEWER / EVALUATION_AUTHOR /
    EVALUATION_REVIEWER / EVALUATION_ADMIN / RELEASE_APPROVER.
    """

    def is_authorized(self, actor_role: str, action: str) -> bool: ...

    def can_view_sensitive_evidence(self, actor_role: str) -> bool:
        """11-security §"数据保护": "Report 默认展示聚合分数；case-level evidence 需要更高
        权限."
        """
        ...


class GraderRegistryPort(Protocol):
    """13-package-and-class-design `infrastructure/graders/registry.py`. Satisfied by
    infrastructure.graders.registry.GraderRegistry — application code reaches it only
    through this Protocol (the import-linter "application must not depend on
    infrastructure" contract).
    """

    def grade(
        self, dimension: EvaluationDimension, grader_type: GraderType, test_case: EvaluationTestCase, result: CaseExecutionResult,
    ) -> GraderResult: ...

    def dimensions_for_case(self, test_case: EvaluationTestCase) -> tuple[tuple[EvaluationDimension, GraderType], ...]:
        """Which (dimension, grader_type) pairs apply to a given case — e.g. a case
        with no `allowedTools`/`forbiddenTools` does not need a TOOL_SELECTION grade.
        """
        ...


class EventPublisherPort(Protocol):
    """08-transaction-and-outbox §"Outbox Publisher". Only DispatchOutboxEventsService
    may depend on this port.
    """

    def publish(self, record: OutboxRecord) -> bool:
        """Returns True on success. Must never raise for an ordinary delivery
        failure — DispatchOutboxEventsService interprets False as "retry with
        backoff".
        """
        ...
