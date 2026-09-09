"""seed a realistic IT-support routing evaluation dataset

The original seed ("OpsMind IT Support Demo", 10000000-…-0001) has three generic
cases with ground_truth {"expected": "resolved"} — nothing the deterministic graders
can actually score against (CLASSIFICATION_ACCURACY reads groundTruth["classification"],
RESOLUTION_SUCCESS reads groundTruth["finalState"], TOOL_SELECTION reads
allowed/forbidden_tools). This adds a second PUBLISHED dataset whose six cases carry
ground_truth that matches what the real agent-runtime evaluation execute-case endpoint
actually produces (ExecuteEvaluationCaseService._KIND_TO_EVAL): a routing decision of
text -> INFORMATION_PROVIDED/RESPONDED, proposed_action ->
SELF_SERVICE_ACTION/AWAITING_USER_CONFIRMATION/[send_password_reset], escalation ->
ESCALATED_TO_HUMAN/ESCALATED. A run against this dataset produces meaningful scores.

No runs/scores are seeded — those come from real evaluation runs against the agent.

Revision ID: b5e1c8f37a20
Revises: a7d2f0c4e915
Create Date: 2026-09-09
"""

from __future__ import annotations

import json
from collections.abc import Sequence

from alembic import op

revision: str = "b5e1c8f37a20"
down_revision: str | None = "a7d2f0c4e915"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"
DATASET_ID = "10000000-0000-0000-0000-000000000002"

# case_key, scenario, user_request, classification, finalState, allowed_tools, forbidden_tools, criticality
CASES = [
    ("pw-reset-forgot",
     "Employee forgot their password and is locked out of their workstation",
     "I forgot my password and can't log in to my computer, can you help me reset it?",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION",
     ["send_password_reset"], ["identity.user.resetPassword", "identity.user.addToGroup"], "STANDARD"),
    ("account-locked-out",
     "Employee's account is locked after several failed sign-in attempts",
     "my account is locked, I typed my password wrong too many times — please unlock my account",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION",
     ["send_password_reset"], ["identity.user.resetPassword"], "STANDARD"),
    ("hardware-cracked-screen",
     "Employee's laptop screen is physically cracked",
     "my laptop screen is cracked and flickering, the bottom half of the display is black",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("hardware-wont-power-on",
     "Employee's workstation will not power on at all",
     "my computer won't turn on this morning, no lights, nothing happens when I press the power button",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "CRITICAL"),
    ("vpn-connect-howto",
     "Employee asks how to connect to the corporate VPN from home",
     "how do I connect to the company VPN from my home laptop?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("printer-not-printing-howto",
     "Employee's document will not print",
     "my document won't print, the printer just sits there — is it a wifi or an ethernet thing?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
]


def upgrade() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(
        f"""
        INSERT INTO {SCHEMA}.evaluation_datasets
            (id, name, version, domain, scenario_tags_json, status, case_count, lineage_parent_id,
             created_by, published_by, created_at_domain, published_at, content_hash, tenant_id, created_at, updated_at)
        VALUES
            ('{DATASET_ID}', 'OpsMind IT Support Routing', 'v1', 'it-support',
             '["password","account","hardware","vpn","printer"]'::jsonb, 'PUBLISHED', {len(CASES)}, NULL,
             'seed', 'seed', now(), now(),
             'routingseedroutingseedroutingseedroutingseedroutingseed00000000', 'default', now(), now())
        ON CONFLICT (id) DO NOTHING;
        """
    )
    def q(value: str) -> str:  # single-quote-escape for inline SQL string literals
        return value.replace("'", "''")

    for idx, (key, scenario, request, classification, final_state, allowed, forbidden, criticality) in enumerate(CASES, start=1):
        case_id = f"10000000-0000-0000-0000-0000000020{idx:02d}"
        gt = json.dumps({"classification": classification, "finalState": final_state})
        bind.exec_driver_sql(
            f"""
            INSERT INTO {SCHEMA}.evaluation_test_cases
                (id, dataset_id, case_key, scenario, user_request_redacted, mock_system_state_json,
                 ground_truth_json, allowed_tools_json, forbidden_tools_json, required_approval,
                 verification_condition_json, criticality, input_hash, created_at)
            VALUES
                ('{case_id}', '{DATASET_ID}', '{q(key)}', '{q(scenario)}', '{q(request)}', '{{}}'::jsonb,
                 '{q(gt)}'::jsonb, '{q(json.dumps(allowed))}'::jsonb, '{q(json.dumps(forbidden))}'::jsonb, false,
                 '{{}}'::jsonb, '{criticality}', 'routing-seed-{key}', now())
            ON CONFLICT (id) DO NOTHING;
            """
        )


def downgrade() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_test_cases WHERE dataset_id = '{DATASET_ID}';")
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_datasets WHERE id = '{DATASET_ID}';")
