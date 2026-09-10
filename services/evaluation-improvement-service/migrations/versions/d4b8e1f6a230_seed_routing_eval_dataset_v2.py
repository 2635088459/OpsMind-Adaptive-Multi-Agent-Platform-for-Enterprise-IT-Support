"""seed the v2 IT-support routing evaluation dataset (33 cases)

SPEC-XEVAL-001 / SPEC-XPROMPT-001: the "extended" dataset (c9f4a1b7e230,
10000000-…-0003, 22 cases) drove the v2 routing-prompt change to 0.955 — the one
residual miss was `second-monitor-request`, phrased as a *question* ("...how do I
get one?") but ground-truthed ESCALATED_TO_HUMAN. Under the v2 prompt a how-to
question is answered, not auto-escalated, so that ground truth was wrong, not the
agent. This dataset resolves it by splitting the case in two, one on each side of
the boundary the v2 prompt actually draws:
  - `second-monitor-howto`             "...how do I get one?"        -> INFORMATION_PROVIDED
  - `second-monitor-request-imperative`"...can you get one set up for me" -> ESCALATED_TO_HUMAN

It also widens coverage: 9 how-to, 8 password self-service, 8 hardware escalation,
8 explicit "do it / grant access for me" escalation. The nightly real-model
accuracy run (agent-accuracy-nightly.yml) and scripts/agent-accuracy-eval.sh now
default to this dataset (10000000-…-0004); CI's fast deterministic gate still uses
the 6-case set (10000000-…-0002).

Ground truth matches ExecuteEvaluationCaseService._KIND_TO_EVAL exactly — the only
three combinations the agent-runtime evaluation endpoint can produce:
  INFORMATION_PROVIDED / RESPONDED                         (how-to / informational)
  SELF_SERVICE_ACTION  / AWAITING_USER_CONFIRMATION / [send_password_reset]
  ESCALATED_TO_HUMAN   / ESCALATED                         (hardware, access grants,
                                                           explicit "open a ticket")

No runs/scores seeded — those come from real evaluation runs against the agent.

Revision ID: d4b8e1f6a230
Revises: c9f4a1b7e230
Create Date: 2026-09-10
"""

from __future__ import annotations

import json
from collections.abc import Sequence

from alembic import op

revision: str = "d4b8e1f6a230"
down_revision: str | None = "c9f4a1b7e230"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None

SCHEMA = "evaluation"
DATASET_ID = "10000000-0000-0000-0000-000000000004"

_PW_FORBIDDEN = ["identity.user.resetPassword", "identity.user.addToGroup"]

# case_key, scenario, user_request, classification, finalState, allowed_tools, forbidden_tools, criticality
CASES = [
    # --- informational / how-to -> INFORMATION_PROVIDED / RESPONDED --------------
    ("vpn-howto-mobile",
     "Employee asks how to set up the corporate VPN on their phone",
     "how do I set up the company VPN on my iPhone? I already have it on my laptop.",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("wifi-guest-howto",
     "Employee asks how visitors connect to guest wifi",
     "a client is coming in tomorrow — how do they get on the guest wifi?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("printer-add-howto",
     "Employee asks how to add a specific office printer",
     "how do I add the 3rd-floor printer to my Mac? I can see it in the list but it won't add.",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("outlook-ooo-howto",
     "Employee asks how to set an out-of-office auto-reply",
     "how do I turn on an out-of-office automatic reply in Outlook for next week?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("password-policy-question",
     "Employee asks what the password requirements are",
     "what are the password rules here? how many characters and do I need a symbol?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("software-request-process",
     "Employee asks how to request a paid software license",
     "how do I request a Photoshop license? is there a form or do I email someone?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("second-monitor-howto",
     "Employee asks how to go about getting an additional monitor for home",
     "I'd like a second monitor for working from home — how do I get one?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("conf-room-cast-howto",
     "Employee asks how to cast their screen to a conference-room display",
     "how do I share my screen to the TV in the 4th-floor conference room?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),
    ("vpn-split-tunnel-question",
     "Employee asks which VPN profile they should use",
     "what's the difference between the 'full' and 'split' VPN profiles, and which one should I be on?",
     "INFORMATION_PROVIDED", "RESPONDED", [], [], "STANDARD"),

    # --- password / account self-service -> SELF_SERVICE_ACTION -----------------
    ("pw-expired-cannot-login",
     "Employee's password expired and they cannot sign in",
     "my password expired over the weekend and now it won't let me log in at all.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),
    ("pw-suspected-compromise",
     "Employee thinks their password may be compromised and wants it reset",
     "I think someone may have my password — I want to reset it right now to be safe.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),
    ("account-locked-mfa-fails",
     "Employee is locked out after repeated failed MFA prompts",
     "I kept failing the MFA push and now I'm completely locked out, I need to get back in.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], ["identity.user.resetPassword"], "STANDARD"),
    ("forgot-password-remote",
     "Remote employee forgot their password and cannot reach the help desk",
     "working from home and I've forgotten my password, I can't get through to the IT desk.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),
    ("new-hire-temp-password",
     "New hire's temporary password is not working on first login",
     "it's my first day and the temporary password in the welcome email doesn't work.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),
    ("locked-after-leave",
     "Employee returns from extended leave to a locked account",
     "back after a month of parental leave and my account looks locked — can't sign in.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),
    ("sso-credentials-rejected",
     "Employee cannot get past the SSO login page despite correct credentials",
     "the SSO login keeps saying my credentials are invalid even though I know they're right — I need back in.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),
    ("explicit-password-reset-link",
     "Employee explicitly asks for a password-reset link to their email",
     "can you send me a password reset link to my email? I want to change it now.",
     "SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ["send_password_reset"], _PW_FORBIDDEN, "STANDARD"),

    # --- hardware / cannot self-serve -> ESCALATED_TO_HUMAN --------------------
    ("laptop-liquid-damage",
     "Employee spilled liquid on their laptop and the keyboard stopped working",
     "I spilled coffee on my laptop and now half the keyboard doesn't respond.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("monitor-dead-pixels-spreading",
     "Employee's external monitor has a growing area of dead pixels",
     "there's a black patch on my external monitor and it's getting bigger every day.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("laptop-overheating-shutdown",
     "Employee's laptop overheats and shuts down at random",
     "my laptop gets really hot and just shuts off randomly, a few times a day now.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "CRITICAL"),
    ("docking-station-dead",
     "Employee's docking station stopped charging and its ethernet port is dead",
     "my dock won't charge the laptop anymore and the ethernet port on it is dead too.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("battery-swelling-safety",
     "Employee's laptop battery is visibly swelling",
     "my laptop battery is bulging and the trackpad is being pushed up — is that dangerous?",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "CRITICAL"),
    ("keyboard-keys-dead",
     "Several keys on the employee's built-in keyboard have stopped working",
     "three keys on my laptop keyboard have completely stopped working, nothing happens when I press them.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("desk-phone-no-dial-tone",
     "The employee's desk phone is unresponsive",
     "the desk phone at my workstation has no dial tone and the screen is blank.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("headset-mic-broken",
     "The employee's company headset has physical damage",
     "the boom mic on my company headset snapped off — people can't hear me on calls at all.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),

    # --- explicit "do it for me" / access-grant -> ESCALATED_TO_HUMAN ----------
    # (a person has to act — the agent opens the ticket rather than explaining the
    #  manual request process; SPEC-XPROMPT-001)
    ("open-ticket-app-access",
     "Employee asks the assistant to file a ticket so IT grants them access to an application",
     "since I need access to log into StarRez, help me open a ticket to have the IT team give me the access.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("request-shared-mailbox-access",
     "Employee wants access to a shared mailbox provisioned for them",
     "please request access to the facilities shared mailbox for me — I need it for my new role.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("explicit-open-ticket-generic",
     "Employee explicitly asks for a ticket to be opened for a non-hardware issue",
     "can you open a ticket for this and have someone from IT get back to me?",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("provision-vpn-for-contractor",
     "Employee asks IT to provision VPN access for a contractor on their team",
     "I need the IT team to set up VPN access for a contractor on my team — can you get that started?",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("second-monitor-request-imperative",
     "Employee tells the assistant to arrange a second monitor for them",
     "can you get a second monitor set up for my home office? I need one for the new project.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("add-to-distribution-list",
     "Employee asks for a ticket to be added to a distribution list",
     "file a ticket to get me added to the finance-announcements distribution list.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("order-replacement-laptop",
     "Employee asks the assistant to get a replacement laptop ordered",
     "my laptop is 5 years old and painfully slow — can you get a replacement ordered for me?",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
    ("jira-account-for-intern",
     "Employee tells the assistant to provision an account for a new joiner",
     "set up a Jira account for our new intern who starts Monday.",
     "ESCALATED_TO_HUMAN", "ESCALATED", [], [], "STANDARD"),
]


def upgrade() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(
        f"""
        INSERT INTO {SCHEMA}.evaluation_datasets
            (id, name, version, domain, scenario_tags_json, status, case_count, lineage_parent_id,
             created_by, published_by, created_at_domain, published_at, content_hash, tenant_id, created_at, updated_at)
        VALUES
            ('{DATASET_ID}', 'OpsMind IT Support Routing (v2)', 'v1', 'it-support',
             '["password","account","hardware","vpn","printer","wifi","software","access"]'::jsonb, 'PUBLISHED', {len(CASES)},
             '10000000-0000-0000-0000-000000000003',
             'seed', 'seed', now(), now(),
             'routingv2seedroutingv2seedroutingv2seedroutingv2seed000000000000', 'default', now(), now())
        ON CONFLICT (id) DO NOTHING;
        """
    )

    def q(value: str) -> str:  # single-quote-escape for inline SQL string literals
        return value.replace("'", "''")

    for idx, (key, scenario, request, classification, final_state, allowed, forbidden, criticality) in enumerate(CASES, start=1):
        case_id = f"10000000-0000-0000-0000-0000000040{idx:02d}"
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
                 '{{}}'::jsonb, '{criticality}', 'routing-v2-{key}', now())
            ON CONFLICT (id) DO NOTHING;
            """
        )


def downgrade() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_test_cases WHERE dataset_id = '{DATASET_ID}';")
    bind.exec_driver_sql(f"DELETE FROM {SCHEMA}.evaluation_datasets WHERE id = '{DATASET_ID}';")
