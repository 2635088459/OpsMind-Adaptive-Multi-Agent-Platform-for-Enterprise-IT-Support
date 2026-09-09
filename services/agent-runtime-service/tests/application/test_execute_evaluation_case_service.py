"""SPEC-EI-013 follow-up: ExecuteEvaluationCaseService runs the real
ConversationReasoningPort on an evaluation case and reports the derived routing
decision + the LLM call's real prompt/completion token split.
"""

from __future__ import annotations

import pytest

from agentruntime.application.commands import ExecuteEvaluationCaseCommand
from agentruntime.application.records import ReasoningOutcome
from agentruntime.application.services.execute_evaluation_case import ExecuteEvaluationCaseService

pytestmark = pytest.mark.unit


class _StubReasoning:
    def __init__(self, outcome: ReasoningOutcome) -> None:
        self.outcome = outcome
        self.seen_message: str | None = None

    def decide(self, message_text, knowledge_snippets, attachments=None):  # noqa: ANN001, ANN201, ARG002
        self.seen_message = message_text
        return self.outcome


def _command(message: str = "I need to reset my password") -> ExecuteEvaluationCaseCommand:
    return ExecuteEvaluationCaseCommand(
        run_id="run-1", run_generation=1, target_version="agent-v1.1.0", test_case_id="tc-1",
        case_key="case-1", scenario="Employee cannot log in", message=message,
    )


def test_proposed_action_maps_to_self_service_and_carries_the_token_split() -> None:
    stub = _StubReasoning(ReasoningOutcome(
        kind="proposed_action", action_summary="Send a password-reset link.", action_risk_level="LOW",
        prompt_tokens=812, completion_tokens=143,
    ))
    view = ExecuteEvaluationCaseService(stub).execute_case(_command())

    assert view.classification == "SELF_SERVICE_ACTION"
    assert view.final_state == "AWAITING_USER_CONFIRMATION"
    assert view.tool_calls == ("send_password_reset",)
    assert view.prompt_tokens == 812
    assert view.completion_tokens == 143
    assert view.latency_ms >= 0
    assert stub.seen_message == "I need to reset my password"


def test_text_and_escalation_derivations() -> None:
    text_view = ExecuteEvaluationCaseService(
        _StubReasoning(ReasoningOutcome(kind="text", text="Restart the VPN client."))
    ).execute_case(_command())
    assert text_view.classification == "INFORMATION_PROVIDED"
    assert text_view.final_state == "RESPONDED"
    assert text_view.tool_calls == ()
    assert text_view.explanation_text == "Restart the VPN client."

    esc_view = ExecuteEvaluationCaseService(
        _StubReasoning(ReasoningOutcome(kind="escalation", escalation_reason="Hardware inspection needed."))
    ).execute_case(_command())
    assert esc_view.classification == "ESCALATED_TO_HUMAN"
    assert esc_view.final_state == "ESCALATED"


def test_falls_back_to_scenario_when_no_message_text() -> None:
    stub = _StubReasoning(ReasoningOutcome(kind="text", text="ok"))
    ExecuteEvaluationCaseService(stub).execute_case(_command(message=""))
    assert stub.seen_message == "Employee cannot log in"
