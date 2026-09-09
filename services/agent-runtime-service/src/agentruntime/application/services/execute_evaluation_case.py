"""13-package-and-class-design §"Application Layer": ExecuteEvaluationCaseService, the
sole implementation of ExecuteEvaluationCasePort.

SPEC-EI-013 follow-up. evaluation-improvement-service historically ran every eval case
through its own FakeAgentRuntimeEvaluationAdapter (a deterministic simulator that reads
the case's own ground truth). This service is the real counterpart: it runs the case's
message through the live ConversationReasoningPort — the same reasoning the employee
portal hits — and reports the agent's routing decision plus the LLM call's real
prompt/completion token cost. It never creates a Ticket, advances a Workflow, or
executes a tool (domain-rules "forbidden": direct_ticket_state_write /
direct_workflow_state_write / direct_tool_execution) — reasoning is the thing under
test, exactly as SdkLangSmithExperimentAdapter's own docstring already frames it.

The ReasoningOutcome -> (classification, final_state, tool_calls) mapping here is a
routing-quality signal, not a full taxonomy classifier: the conversational agent
produces a text answer / a proposed action / an escalation, and this reports which,
in the shape evaluation-improvement-service's graders already read.
"""

from __future__ import annotations

import time

from agentruntime.application.commands import ExecuteEvaluationCaseCommand
from agentruntime.application.ports_out import ConversationReasoningPort
from agentruntime.application.views import EvaluationCaseExecutionView

_KIND_TO_EVAL = {
    "text": ("INFORMATION_PROVIDED", "RESPONDED", ()),
    "proposed_action": ("SELF_SERVICE_ACTION", "AWAITING_USER_CONFIRMATION", ("send_password_reset",)),
    "escalation": ("ESCALATED_TO_HUMAN", "ESCALATED", ()),
}


class ExecuteEvaluationCaseService:
    def __init__(self, reasoning_port: ConversationReasoningPort) -> None:
        self._reasoning_port = reasoning_port

    def execute_case(self, command: ExecuteEvaluationCaseCommand) -> EvaluationCaseExecutionView:
        started = time.monotonic()
        outcome = self._reasoning_port.decide(command.message or command.scenario, [], None)
        latency_ms = int((time.monotonic() - started) * 1000)

        classification, final_state, tool_calls = _KIND_TO_EVAL.get(
            outcome.kind, ("INFORMATION_PROVIDED", "RESPONDED", ())
        )
        if outcome.kind == "text":
            explanation = outcome.text or ""
        elif outcome.kind == "proposed_action":
            explanation = outcome.action_summary or ""
        else:
            explanation = outcome.escalation_reason or ""

        return EvaluationCaseExecutionView(
            classification=classification,
            final_state=final_state,
            tool_calls=tool_calls,
            explanation_text=explanation,
            prompt_tokens=outcome.prompt_tokens,
            completion_tokens=outcome.completion_tokens,
            latency_ms=latency_ms,
        )
