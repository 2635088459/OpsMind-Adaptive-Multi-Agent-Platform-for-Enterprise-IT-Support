"""SPEC-EI-002 / 07-data-model: SQLAlchemy ORM models for the `evaluation` PostgreSQL
schema. This module is the entire ORM boundary — application and domain code never
see these classes (enforced by import-linter's "Application must not depend on
infrastructure" contract); infrastructure.persistence.postgres.repositories
translates between these rows and the application-layer domain objects.

07-data-model names six core tables (evaluation_datasets, evaluation_test_cases,
evaluation_runs, evaluation_scores, regression_reports, improvement_candidates,
built by SPEC-EI-002) plus outbox_events/processed_events/audit_records (built here
by SPEC-EI-003). `evaluation_gate_policies`, `evaluation_case_execution_results`, and
`evaluation_command_idempotency` are pragmatic extensions beyond that literal list —
the same way memory-knowledge-service's own SPEC-MK-002/003 added `command_idempotency`
beyond 07-data-model's own list there — since application.ports_out.
GatePolicyRepository/CaseExecutionResultRepository/CommandIdempotencyRepository need
a real backend too.

Every table carries a `created_at`/`updated_at` audit-timestamp pair
(persistence_CN.md §"持久化规则": "所有表必须包含 stable id、created_at/updated_at 或等价时间
字段") populated by the database itself (`server_default=func.now()`), independent of
whether the corresponding domain aggregate carries its own timestamp fields — these
are bookkeeping columns, never round-tripped back into a domain object.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, MetaData, Numeric, String, Text, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

SCHEMA = "evaluation"


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA)


class EvaluationDatasetRow(Base):
    __tablename__ = "evaluation_datasets"
    __table_args__ = (
        UniqueConstraint("name", "version", name="uq_evaluation_datasets_name_version"),
        Index("ix_evaluation_datasets_tenant_id", "tenant_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    version: Mapped[str] = mapped_column(String(50), nullable=False)
    domain: Mapped[str] = mapped_column(String(100), nullable=False)
    scenario_tags_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    case_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    lineage_parent_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_datasets.id"), nullable=True
    )
    created_by: Mapped[str] = mapped_column(String(200), nullable=False)
    published_by: Mapped[str | None] = mapped_column(String(200), nullable=True)
    created_at_domain: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # SPEC-EI-007: frozen at publish() time, NULL before then — see
    # domain.dataset.EvaluationDataset's own docstring.
    content_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # SPEC-EI-008: caller-asserted tenant scope — see
    # domain.dataset.EvaluationDataset's own docstring.
    tenant_id: Mapped[str] = mapped_column(String(100), nullable=False, server_default="default")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class EvaluationTestCaseRow(Base):
    __tablename__ = "evaluation_test_cases"
    __table_args__ = (UniqueConstraint("dataset_id", "case_key", name="uq_evaluation_test_cases_dataset_id_case_key"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    dataset_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_datasets.id"), nullable=False)
    case_key: Mapped[str] = mapped_column(String(200), nullable=False)
    scenario: Mapped[str] = mapped_column(String(500), nullable=False)
    user_request_redacted: Mapped[str] = mapped_column(Text, nullable=False, default="")
    mock_system_state_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    ground_truth_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    allowed_tools_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    forbidden_tools_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    required_approval: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    verification_condition_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    criticality: Mapped[str] = mapped_column(String(20), nullable=False)
    input_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class EvaluationRunRow(Base):
    __tablename__ = "evaluation_runs"
    __table_args__ = (UniqueConstraint("run_key", name="uq_evaluation_runs_run_key"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    run_key: Mapped[str] = mapped_column(String(200), nullable=False)
    dataset_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_datasets.id"), nullable=False)
    dataset_version: Mapped[str] = mapped_column(String(50), nullable=False)
    target_version: Mapped[str] = mapped_column(String(200), nullable=False)
    baseline_version: Mapped[str | None] = mapped_column(String(200), nullable=True)
    grader_bundle_version: Mapped[str] = mapped_column(String(50), nullable=False)
    policy_version: Mapped[str] = mapped_column(String(50), nullable=False)
    correlation_id: Mapped[str] = mapped_column(String(200), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    triggered_by: Mapped[str] = mapped_column(String(200), nullable=False)
    # 09-concurrency-and-idempotency §"Stale 结果": bumped whenever a run restarts case
    # execution. Nothing in SPEC-EI-001/002's own scope ever increments this past its
    # default of 1 — see application.ports_out.EvaluationRunRepository.
    # current_generation()'s own docstring — kept here so the column already exists
    # once a later spec needs to write it.
    generation: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class EvaluationScoreRow(Base):
    __tablename__ = "evaluation_scores"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    run_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=False)
    test_case_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_test_cases.id"), nullable=False)
    dimension: Mapped[str] = mapped_column(String(50), nullable=False)
    score: Mapped[float] = mapped_column(Numeric, nullable=False)
    passed: Mapped[bool] = mapped_column(Boolean, nullable=False)
    threshold: Mapped[float] = mapped_column(Numeric, nullable=False)
    grader_type: Mapped[str] = mapped_column(String(20), nullable=False)
    grader_version: Mapped[str] = mapped_column(String(100), nullable=False)
    evidence_ref_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    failure_code: Mapped[str | None] = mapped_column(String(50), nullable=True)
    details_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class RegressionReportRow(Base):
    __tablename__ = "regression_reports"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    run_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=False)
    baseline_run_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=True)
    overall_decision: Mapped[str] = mapped_column(String(20), nullable=False)
    metric_diffs_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    gate_results_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    critical_failures_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    recommendation: Mapped[str] = mapped_column(Text, nullable=False)
    created_at_domain: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class ImprovementCandidateRow(Base):
    __tablename__ = "improvement_candidates"
    __table_args__ = (
        UniqueConstraint(
            "source_run_id", "source_failure_cluster_id", "target_component",
            name="uq_improvement_candidates_natural_key",
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    candidate_type: Mapped[str] = mapped_column(String(50), nullable=False)
    source_run_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=False)
    source_failure_cluster_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    target_component: Mapped[str] = mapped_column(String(200), nullable=False)
    proposed_change_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    risk_level: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    created_by: Mapped[str] = mapped_column(String(200), nullable=False)
    benchmark_passed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    approval_request_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    approved_by: Mapped[str | None] = mapped_column(String(200), nullable=True)
    canary_plan_json: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    canary_status: Mapped[str | None] = mapped_column(String(30), nullable=True)
    promoted_version: Mapped[str | None] = mapped_column(String(200), nullable=True)
    created_at_domain: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at_domain: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class GatePolicyRow(Base):
    """Not in 07-data-model's own literal table list — see this module's own
    docstring. `gate_policy` (the name) is the natural primary key: 05-api-contracts
    addresses it as `GET/PUT /evaluation/gates/{gatePolicy}`, never a separate id.
    """

    __tablename__ = "evaluation_gate_policies"

    gate_policy: Mapped[str] = mapped_column(String(100), primary_key=True)
    dimension_thresholds_json: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    critical_case_required: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    max_policy_violations: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    max_forbidden_tool_calls: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    max_unauthorized_memory_access: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class CaseExecutionResultRow(Base):
    """Not in 07-data-model's own literal table list — see this module's own
    docstring. The natural key is `(run_id, test_case_id)`, matching
    InMemoryCaseExecutionResultRepository's own dict key exactly — a case is
    re-executed in place (09-concurrency-and-idempotency §"并发规则": "同一个 run 的同一
    个 case 可以重试"), never appended as a new row.
    """

    __tablename__ = "evaluation_case_execution_results"

    run_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_runs.id"), primary_key=True)
    test_case_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey(f"{SCHEMA}.evaluation_test_cases.id"), primary_key=True
    )
    run_generation: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    final_state: Mapped[str] = mapped_column(String(100), nullable=False, default="")
    tool_calls_json: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    classification: Mapped[str] = mapped_column(String(100), nullable=False, default="")
    policy_violation_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    forbidden_tool_call_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    unauthorized_memory_access_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    cost_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    latency_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    workflow_trace_ref: Mapped[str] = mapped_column(String(500), nullable=False, default="")
    # SPEC-EI-009: replaces the earlier `completed` boolean — see
    # application.records.CaseExecutionResult's own docstring.
    status: Mapped[str] = mapped_column(String(20), nullable=False, server_default="COMPLETED")
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class OutboxEventRow(Base):
    """SPEC-EI-003 / 08-transaction-and-outbox §"Outbox 发布". `payload` is the fully
    encoded 06-event-contracts envelope JSON string application.outbox_codec already
    produces — stored verbatim (Text, not JSONB) so what a real broker eventually
    publishes is byte-for-byte the row this table held, never a value reshaped by a
    JSONB round trip.
    """

    __tablename__ = "evaluation_outbox_events"
    __table_args__ = (Index("ix_evaluation_outbox_events_status_available_at", "status", "available_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    event_type: Mapped[str] = mapped_column(String(100), nullable=False)
    schema_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    aggregate_id: Mapped[str] = mapped_column(String(200), nullable=False)
    payload: Mapped[str] = mapped_column(Text, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    correlation_id: Mapped[str] = mapped_column(String(200), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    available_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class ProcessedEventRow(Base):
    """SPEC-EI-003 / 09-concurrency-and-idempotency: "07 消费外部事件时写 processed_events.
    重复 event 返回已处理结果." The natural key *is* the primary key — a row's mere
    existence for (event_id, consumer_name) is the dedup check, never a separate
    uniqueness lookup.
    """

    __tablename__ = "evaluation_processed_events"

    event_id: Mapped[str] = mapped_column(String(200), primary_key=True)
    consumer_name: Mapped[str] = mapped_column(String(200), primary_key=True)
    event_type: Mapped[str | None] = mapped_column(String(100), nullable=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CommandIdempotencyRow(Base):
    """SPEC-EI-003 / 09-concurrency-and-idempotency §"幂等键". Not in 07-data-model's
    own literal table list — see this module's own docstring. `idempotency_key` is
    the natural primary key: persistence_CN.md §"索引与约束": "command idempotency key
    必须有唯一约束."
    """

    __tablename__ = "evaluation_command_idempotency"

    idempotency_key: Mapped[str] = mapped_column(String(300), primary_key=True)
    command_type: Mapped[str] = mapped_column(String(100), nullable=False)
    target_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    request_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    response_json: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AuditRecordRow(Base):
    """SPEC-EI-003 / 12-observability §"Audit Events". Append-only — no update method
    on the matching repository, mirroring memory-knowledge-service's own
    AuditEventRow precedent exactly.
    """

    __tablename__ = "evaluation_audit_records"
    __table_args__ = (Index("ix_evaluation_audit_records_occurred_at", "occurred_at"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    action: Mapped[str] = mapped_column(String(100), nullable=False)
    resource_type: Mapped[str] = mapped_column(String(100), nullable=False)
    resource_id: Mapped[str] = mapped_column(String(200), nullable=False)
    actor: Mapped[str] = mapped_column(String(200), nullable=False)
    outcome: Mapped[str] = mapped_column(String(20), nullable=False)
    correlation_id: Mapped[str | None] = mapped_column(String(200), nullable=True)
    detail: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
