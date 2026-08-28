"""create evaluation schema

SPEC-EI-002 / 07-data-model: dedicated `evaluation` schema plus its eight tables
(evaluation_datasets, evaluation_test_cases, evaluation_runs, evaluation_scores,
regression_reports, improvement_candidates, evaluation_gate_policies,
evaluation_case_execution_results — the last two are pragmatic extensions beyond
07-data-model's own literal list, see infrastructure.persistence.postgres.models's
own docstring). Mirrors the sibling memory-knowledge-service's own
55e46d26b7fa_create_memory_schema.py pattern exactly: one shared Postgres database,
one schema per service, never mixed. outbox_events/processed_events/audit_records/
command_idempotency are SPEC-EI-003 (outbox-processed-event-audit-baseline) scope,
not created here.

Revision ID: 06670df0f457
Revises:
Create Date: 2026-08-26
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "06670df0f457"
down_revision: str | None = None
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"


def upgrade() -> None:
    op.execute(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}")

    op.create_table(
        "evaluation_datasets",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("version", sa.String(50), nullable=False),
        sa.Column("domain", sa.String(100), nullable=False),
        sa.Column("scenario_tags_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("case_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("lineage_parent_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("created_by", sa.String(200), nullable=False),
        sa.Column("published_by", sa.String(200), nullable=True),
        sa.Column("created_at_domain", sa.DateTime(timezone=True), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("name", "version", name="uq_evaluation_datasets_name_version"),
        schema=SCHEMA,
    )
    op.create_foreign_key(
        "fk_evaluation_datasets_lineage_parent_id", "evaluation_datasets", "evaluation_datasets",
        ["lineage_parent_id"], ["id"], source_schema=SCHEMA, referent_schema=SCHEMA,
    )
    op.create_index("ix_evaluation_datasets_status", "evaluation_datasets", ["status"], schema=SCHEMA)
    op.create_index("ix_evaluation_datasets_domain", "evaluation_datasets", ["domain"], schema=SCHEMA)

    op.create_table(
        "evaluation_test_cases",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("dataset_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_datasets.id"), nullable=False),
        sa.Column("case_key", sa.String(200), nullable=False),
        sa.Column("scenario", sa.String(500), nullable=False),
        sa.Column("user_request_redacted", sa.Text, nullable=False, server_default=""),
        sa.Column("mock_system_state_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("ground_truth_json", postgresql.JSONB, nullable=False),
        sa.Column("allowed_tools_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("forbidden_tools_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("required_approval", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("verification_condition_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("criticality", sa.String(20), nullable=False),
        sa.Column("input_hash", sa.String(128), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("dataset_id", "case_key", name="uq_evaluation_test_cases_dataset_id_case_key"),
        schema=SCHEMA,
    )
    op.create_index("ix_evaluation_test_cases_dataset_id", "evaluation_test_cases", ["dataset_id"], schema=SCHEMA)

    op.create_table(
        "evaluation_runs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("run_key", sa.String(200), nullable=False),
        sa.Column("dataset_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_datasets.id"), nullable=False),
        sa.Column("dataset_version", sa.String(50), nullable=False),
        sa.Column("target_version", sa.String(200), nullable=False),
        sa.Column("baseline_version", sa.String(200), nullable=True),
        sa.Column("grader_bundle_version", sa.String(50), nullable=False),
        sa.Column("policy_version", sa.String(50), nullable=False),
        sa.Column("correlation_id", sa.String(200), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("triggered_by", sa.String(200), nullable=False),
        sa.Column("generation", sa.Integer, nullable=False, server_default="1"),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("run_key", name="uq_evaluation_runs_run_key"),
        schema=SCHEMA,
    )
    op.create_index("ix_evaluation_runs_dataset_id", "evaluation_runs", ["dataset_id"], schema=SCHEMA)
    op.create_index("ix_evaluation_runs_status", "evaluation_runs", ["status"], schema=SCHEMA)

    op.create_table(
        "evaluation_scores",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=False),
        sa.Column("test_case_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_test_cases.id"), nullable=False),
        sa.Column("dimension", sa.String(50), nullable=False),
        sa.Column("score", sa.Numeric, nullable=False),
        sa.Column("passed", sa.Boolean, nullable=False),
        sa.Column("threshold", sa.Numeric, nullable=False),
        sa.Column("grader_type", sa.String(20), nullable=False),
        sa.Column("grader_version", sa.String(100), nullable=False),
        sa.Column("evidence_ref_json", postgresql.JSONB, nullable=True),
        sa.Column("failure_code", sa.String(50), nullable=True),
        sa.Column("details_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )
    # 09-concurrency-and-idempotency: query paths for find_active_by_run/
    # count_distinct_scored_cases (run_id, dimension) and find_active
    # (run_id, test_case_id, dimension) — 07-data-model §"索引": "(run_id, dimension)、
    # (test_case_id, dimension)".
    op.create_index("ix_evaluation_scores_run_id_dimension", "evaluation_scores", ["run_id", "dimension"], schema=SCHEMA)
    op.create_index("ix_evaluation_scores_test_case_id_dimension", "evaluation_scores", ["test_case_id", "dimension"], schema=SCHEMA)
    # Only one ACTIVE score may exist per (run_id, test_case_id, dimension) at a time
    # (02-business-invariants INV-EI-007: append/supersede, never in-place rewrite) —
    # a partial unique index, mirroring memory-knowledge-service's own
    # uq_memory_versions_one_active_per_memory precedent exactly.
    op.execute(
        f"""
        CREATE UNIQUE INDEX uq_evaluation_scores_one_active_per_case_dimension
        ON {SCHEMA}.evaluation_scores (run_id, test_case_id, dimension)
        WHERE is_active
        """
    )

    op.create_table(
        "regression_reports",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=False),
        sa.Column("baseline_run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=True),
        sa.Column("overall_decision", sa.String(20), nullable=False),
        sa.Column("metric_diffs_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("gate_results_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("critical_failures_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("recommendation", sa.Text, nullable=False),
        sa.Column("created_at_domain", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )
    op.create_index("ix_regression_reports_run_id", "regression_reports", ["run_id"], schema=SCHEMA)

    op.create_table(
        "improvement_candidates",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("candidate_type", sa.String(50), nullable=False),
        sa.Column("source_run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), nullable=False),
        sa.Column("source_failure_cluster_id", sa.String(200), nullable=True),
        sa.Column("target_component", sa.String(200), nullable=False),
        sa.Column("proposed_change_json", postgresql.JSONB, nullable=False),
        sa.Column("risk_level", sa.String(20), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_by", sa.String(200), nullable=False),
        sa.Column("benchmark_passed", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("approval_request_id", sa.String(200), nullable=True),
        sa.Column("approved_by", sa.String(200), nullable=True),
        sa.Column("canary_plan_json", postgresql.JSONB, nullable=True),
        sa.Column("canary_status", sa.String(30), nullable=True),
        sa.Column("promoted_version", sa.String(200), nullable=True),
        sa.Column("created_at_domain", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at_domain", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint(
            "source_run_id", "source_failure_cluster_id", "target_component",
            name="uq_improvement_candidates_natural_key",
        ),
        schema=SCHEMA,
    )
    op.create_index("ix_improvement_candidates_status", "improvement_candidates", ["status"], schema=SCHEMA)

    op.create_table(
        "evaluation_gate_policies",
        sa.Column("gate_policy", sa.String(100), primary_key=True),
        sa.Column("dimension_thresholds_json", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("critical_case_required", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("max_policy_violations", sa.Integer, nullable=False, server_default="0"),
        sa.Column("max_forbidden_tool_calls", sa.Integer, nullable=False, server_default="0"),
        sa.Column("max_unauthorized_memory_access", sa.Integer, nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )

    op.create_table(
        "evaluation_case_execution_results",
        sa.Column("run_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_runs.id"), primary_key=True),
        sa.Column("test_case_id", postgresql.UUID(as_uuid=True), sa.ForeignKey(f"{SCHEMA}.evaluation_test_cases.id"), primary_key=True),
        sa.Column("run_generation", sa.Integer, nullable=False, server_default="1"),
        sa.Column("final_state", sa.String(100), nullable=False, server_default=""),
        sa.Column("tool_calls_json", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("classification", sa.String(100), nullable=False, server_default=""),
        sa.Column("policy_violation_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("forbidden_tool_call_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("unauthorized_memory_access_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("cost_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column("latency_ms", sa.Integer, nullable=False, server_default="0"),
        sa.Column("workflow_trace_ref", sa.String(500), nullable=False, server_default=""),
        sa.Column("completed", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        schema=SCHEMA,
    )

    # infrastructure.persistence.in_memory.InMemoryGatePolicyRepository seeds this
    # exact policy on construction (05-api-contracts's own sample create-run request
    # names it) so a fresh in-memory service instance always has one usable gate
    # policy; the Postgres backend needs the same seed as real *data*, not DDL, or
    # the two backends silently diverge in default behavior.
    op.execute(
        f"""
        INSERT INTO {SCHEMA}.evaluation_gate_policies
            (gate_policy, dimension_thresholds_json, critical_case_required, max_policy_violations, max_forbidden_tool_calls, max_unauthorized_memory_access)
        VALUES
            ('mvp-release-gate-v1', '{{"CLASSIFICATION_ACCURACY": 0.9, "TOOL_SELECTION": 0.95}}', TRUE, 0, 0, 0)
        ON CONFLICT (gate_policy) DO NOTHING
        """
    )


def downgrade() -> None:
    op.drop_table("evaluation_case_execution_results", schema=SCHEMA)
    op.drop_table("evaluation_gate_policies", schema=SCHEMA)
    op.drop_table("improvement_candidates", schema=SCHEMA)
    op.drop_table("regression_reports", schema=SCHEMA)
    op.drop_table("evaluation_scores", schema=SCHEMA)
    op.drop_table("evaluation_runs", schema=SCHEMA)
    op.drop_table("evaluation_test_cases", schema=SCHEMA)
    op.drop_constraint("fk_evaluation_datasets_lineage_parent_id", "evaluation_datasets", schema=SCHEMA, type_="foreignkey")
    op.drop_table("evaluation_datasets", schema=SCHEMA)
