"""13-package-and-class-design `infrastructure/runtime/agent_runtime_client.py`. Real
HTTP integration with 03-agent-runtime-orchestration's own evaluation endpoint is
SPEC-EI-012 (agent-runtime-evaluation-client-contract) scope. This adapter is an
honestly-labeled deterministic simulator — never a network call — that "executes" a
case by reading its own ground truth, so SPEC-EI-001's own create_run -> execute_case
-> score_run -> compare_regression -> evaluate_release_gate pipeline is exercisable
end-to-end without a real Agent Runtime dependency. It never mutates Ticket, Workflow,
or Tool state (domain-rules "forbidden": direct_ticket_state_write /
direct_workflow_state_write / direct_tool_execution) — it only reads
EvaluationTestCase fields already held in this service's own schema.
"""

from __future__ import annotations

from evaluationimprovement.application.records import CaseExecutionResult
from evaluationimprovement.domain.ids import RunId
from evaluationimprovement.domain.test_case import EvaluationTestCase


class FakeAgentRuntimeEvaluationAdapter:
    def execute_case(self, run_id: RunId, target_version: str, test_case: EvaluationTestCase, run_generation: int) -> CaseExecutionResult:  # noqa: ARG002
        """Simulates a "correct" agent by default: classification/final-state match the
        case's own ground truth, tool calls are exactly the allowed set, and no
        policy/forbidden-tool/memory violation occurs. A case's own `mockSystemState`
        may override any of `simulatedClassification`/`simulatedFinalState`/
        `simulatedToolCalls`/`simulatedPolicyViolationCount`/
        `simulatedForbiddenToolCallCount`/`simulatedUnauthorizedMemoryAccessCount` to
        deliberately simulate a wrong or unsafe agent for a test — this is what
        drives every SPEC-EI-001 test that needs a FAILED run through the real
        pipeline instead of hand-constructing a CaseExecutionResult directly.
        SPEC-EI-009: `simulateRunnerError` raises instead, simulating the real Agent
        Runtime call itself failing — what ExecuteCaseService's own try/except now
        turns into a FAILED CaseExecutionResult rather than an unhandled exception.
        """
        state = test_case.mock_system_state
        if state.get("simulateRunnerError"):
            raise RuntimeError(str(state.get("simulateRunnerError")))
        classification = str(state.get("simulatedClassification", test_case.ground_truth.get("classification", "")))
        final_state = str(state.get("simulatedFinalState", test_case.ground_truth.get("finalState", "RESOLVED")))
        tool_calls = tuple(state.get("simulatedToolCalls", test_case.allowed_tools))
        return CaseExecutionResult(
            run_id=str(run_id), test_case_id=str(test_case.test_case_id), run_generation=run_generation,
            final_state=final_state, tool_calls=tool_calls, classification=classification,
            policy_violation_count=int(state.get("simulatedPolicyViolationCount", 0)),
            forbidden_tool_call_count=int(state.get("simulatedForbiddenToolCallCount", 0)),
            unauthorized_memory_access_count=int(state.get("simulatedUnauthorizedMemoryAccessCount", 0)),
            cost_tokens=100, latency_ms=500, workflow_trace_ref=f"fake-trace-{run_id}",
        )
