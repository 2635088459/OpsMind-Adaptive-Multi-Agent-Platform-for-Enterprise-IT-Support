"""seed a demo dataset + baseline/candidate evaluation runs + scores + regression report

Real gap found live 2026-09-08 (frontend integration verification): SPEC-SC-015's
Observability page / EvaluationComparisonTable had no data to render against — the
whole dataset -> run -> score -> regression-report graph must exist before
`GET /evaluation/runs/{id}` returns anything, and nothing seeds it (no runner/worker
is constructed as a running process in this domain's own scope). This adds ONE
realistic, self-consistent comparison so the support-console page shows a real table
end to end (candidate agent-v1.1.0 vs. baseline agent-v1.0.0 across 3 dimensions,
with a deliberate TOOL_SELECTION regression so the "worse than baseline" styling has
something to highlight). Every INSERT is `ON CONFLICT DO NOTHING` so re-running
against a volume that already has these fixed ids is a no-op.

The candidate run id is stable — surface it in the support-console Observability page
or paste it directly: 20000000-0000-0000-0000-000000000002

Revision ID: e1c7a9d4b206
Revises: b8e2f4a6c9d3
Create Date: 2026-09-08
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "e1c7a9d4b206"
down_revision: str | None = "b8e2f4a6c9d3"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"

DATASET_ID = "10000000-0000-0000-0000-000000000001"
CASE_IDS = [
    "10000000-0000-0000-0000-000000001001",
    "10000000-0000-0000-0000-000000001002",
    "10000000-0000-0000-0000-000000001003",
]
BASELINE_RUN_ID = "20000000-0000-0000-0000-000000000001"
CANDIDATE_RUN_ID = "20000000-0000-0000-0000-000000000002"
REPORT_ID = "30000000-0000-0000-0000-000000000001"

# dimension -> (baseline score, candidate score). Candidate improves classification,
# regresses tool selection, holds resolution success.
DIMENSIONS = {
    "CLASSIFICATION_ACCURACY": (0.90, 0.95),
    "TOOL_SELECTION": (0.92, 0.83),
    "RESOLUTION_SUCCESS": (0.88, 0.89),
}
GRADER_TYPE = {"CLASSIFICATION_ACCURACY": "LLM_JUDGE", "TOOL_SELECTION": "DETERMINISTIC", "RESOLUTION_SUCCESS": "DETERMINISTIC"}
THRESHOLD = 0.80


def _score_rows(run_id: str, which: int) -> str:
    rows = []
    for c_idx, case_id in enumerate(CASE_IDS):
        for d_idx, (dim, pair) in enumerate(DIMENSIONS.items()):
            base = pair[which]
            # tiny per-case spread so the averages aren't all identical
            score = round(min(1.0, max(0.0, base + (c_idx - 1) * 0.01)), 4)
            # deterministic id: 4<which><c_idx><d_idx>0000-0000-4000-8000-000000000000
            score_id = f"4{which}{c_idx}{d_idx}0000-0000-4000-8000-000000000000"
            rows.append(
                "('{sid}','{rid}','{cid}','{dim}',{score},{passed},{thr},'{gt}','graders-2026.09.0',NULL,NULL,'{{}}'::jsonb,true,now())".format(
                    sid=score_id, rid=run_id, cid=case_id, dim=dim, score=score,
                    passed="true" if score >= THRESHOLD else "false", thr=THRESHOLD, gt=GRADER_TYPE[dim],
                )
            )
    return ",\n".join(rows)


def upgrade() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(
        f"""
        INSERT INTO {SCHEMA}.evaluation_datasets
            (id, name, version, domain, scenario_tags_json, status, case_count, lineage_parent_id,
             created_by, published_by, created_at_domain, published_at, content_hash, tenant_id, created_at, updated_at)
        VALUES
            ('{DATASET_ID}', 'OpsMind IT Support Demo', 'v1', 'it-support',
             '["vpn","password","hardware"]'::jsonb, 'PUBLISHED', {len(CASE_IDS)}, NULL,
             'seed', 'seed', now(), now(),
             'seedseedseedseedseedseedseedseedseedseedseedseedseedseedseedseed', 'default', now(), now())
        ON CONFLICT (id) DO NOTHING;
        """
    )

    for idx, case_id in enumerate(CASE_IDS):
        bind.exec_driver_sql(
            f"""
            INSERT INTO {SCHEMA}.evaluation_test_cases
                (id, dataset_id, case_key, scenario, user_request_redacted, mock_system_state_json,
                 ground_truth_json, allowed_tools_json, forbidden_tools_json, required_approval,
                 verification_condition_json, criticality, input_hash, created_at)
            VALUES
                ('{case_id}', '{DATASET_ID}', 'case-{idx + 1}', 'Seeded demo scenario {idx + 1}',
                 '[redacted]', '{{}}'::jsonb, '{{"expected":"resolved"}}'::jsonb, '[]'::jsonb, '[]'::jsonb, false,
                 '{{}}'::jsonb, 'STANDARD', 'seed-input-hash-{idx + 1}', now())
            ON CONFLICT (id) DO NOTHING;
            """
        )

    bind.exec_driver_sql(
        f"""
        INSERT INTO {SCHEMA}.evaluation_runs
            (id, run_key, dataset_id, dataset_version, target_version, baseline_version,
             grader_bundle_version, policy_version, correlation_id, status, triggered_by, generation,
             started_at, completed_at, created_at, updated_at)
        VALUES
            ('{BASELINE_RUN_ID}', 'seed-baseline-v1', '{DATASET_ID}', 'v1', 'agent-v1.0.0', NULL,
             'graders-2026.09.0', 'policy-2026.09.0', 'seed-baseline-corr', 'PASSED', 'seed', 1,
             now() - interval '2 hours', now() - interval '110 minutes', now(), now()),
            ('{CANDIDATE_RUN_ID}', 'seed-candidate-v1', '{DATASET_ID}', 'v1', 'agent-v1.1.0', 'agent-v1.0.0',
             'graders-2026.09.0', 'policy-2026.09.0', 'seed-candidate-corr', 'PASSED', 'seed', 1,
             now() - interval '1 hour', now() - interval '50 minutes', now(), now())
        ON CONFLICT (id) DO NOTHING;
        """
    )

    bind.exec_driver_sql(
        f"""
        INSERT INTO {SCHEMA}.evaluation_scores
            (id, run_id, test_case_id, dimension, score, passed, threshold, grader_type, grader_version,
             evidence_ref_json, failure_code, details_json, is_active, created_at)
        VALUES
        {_score_rows(BASELINE_RUN_ID, 0)},
        {_score_rows(CANDIDATE_RUN_ID, 1)}
        ON CONFLICT (id) DO NOTHING;
        """
    )

    bind.exec_driver_sql(
        f"""
        INSERT INTO {SCHEMA}.regression_reports
            (id, run_id, baseline_run_id, overall_decision, metric_diffs_json, gate_results_json,
             critical_failures_json, recommendation, created_at_domain, created_at)
        VALUES
            ('{REPORT_ID}', '{CANDIDATE_RUN_ID}', '{BASELINE_RUN_ID}', 'PASSED',
             '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
             'Candidate agent-v1.1.0 improves classification accuracy but regresses tool selection by ~9 points — safe to canary at a low percentage while monitoring tool-selection.',
             now() - interval '45 minutes', now())
        ON CONFLICT (id) DO NOTHING;
        """
    )


def downgrade() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.regression_reports WHERE id = '{REPORT_ID}'")
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_scores WHERE run_id IN ('{BASELINE_RUN_ID}', '{CANDIDATE_RUN_ID}')")
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_runs WHERE id IN ('{BASELINE_RUN_ID}', '{CANDIDATE_RUN_ID}')")
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_test_cases WHERE dataset_id = '{DATASET_ID}'")
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_datasets WHERE id = '{DATASET_ID}'")
