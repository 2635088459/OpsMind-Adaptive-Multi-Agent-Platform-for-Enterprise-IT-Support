"""13-package-and-class-design `infrastructure/runtime/agent_runtime_client.py`.
SPEC-EI-001 built FakeAgentRuntimeEvaluationAdapter, an honestly-labeled deterministic
simulator — never a network call — that "executes" a case by reading its own ground
truth, so SPEC-EI-001's own create_run -> execute_case -> score_run ->
compare_regression -> evaluate_release_gate pipeline is exercisable end-to-end without
a real Agent Runtime dependency. SPEC-EI-012 adds HttpAgentRuntimeEvaluationAdapter, the
real httpx client against 03-agent-runtime-orchestration's own evaluation endpoint
contract; container.py picks between them via Settings.agent_runtime_evaluation_mode.
Neither adapter ever mutates Ticket, Workflow, or Tool state (domain-rules "forbidden":
direct_ticket_state_write / direct_workflow_state_write / direct_tool_execution) — the
request this module ever sends out is execute-in-mock-state only.
"""

from __future__ import annotations

import httpx

from evaluationimprovement.application.records import CaseExecutionResult
from evaluationimprovement.domain.enums import CaseExecutionStatus
from evaluationimprovement.domain.ids import RunId
from evaluationimprovement.domain.test_case import EvaluationTestCase


class FakeAgentRuntimeEvaluationAdapter:
    def execute_case(self, run_id: RunId, target_version: str, test_case: EvaluationTestCase, run_generation: int) -> CaseExecutionResult:  # noqa: ARG002
        """Simulates a "correct" agent by default: classification/final-state match the
        case's own ground truth, tool calls are exactly the allowed set (each called
        with whatever `groundTruth["expectedToolArgs"]` names for it, if any), approval
        is requested exactly when `test_case.required_approval` says it should be, and
        an independent verification passes. A case's own `mockSystemState` may
        override any of `simulatedClassification`/`simulatedFinalState`/
        `simulatedToolCalls`/`simulatedToolCallArgs`/`simulatedPolicyViolationCount`/
        `simulatedForbiddenToolCallCount`/`simulatedUnauthorizedMemoryAccessCount`/
        `simulatedApprovalTriggered`/`simulatedVerificationPassed`/
        `simulatedExplanationText` to deliberately simulate a wrong or unsafe agent
        for a test — this is what drives every
        SPEC-EI-001 test that needs a FAILED run through the real pipeline instead of
        hand-constructing a CaseExecutionResult directly. SPEC-EI-009:
        `simulateRunnerError` raises instead, simulating the real Agent Runtime call
        itself failing — what ExecuteCaseService's own try/except now turns into a
        FAILED CaseExecutionResult rather than an unhandled exception.
        """
        state = test_case.mock_system_state
        if state.get("simulateRunnerError"):
            raise RuntimeError(str(state.get("simulateRunnerError")))
        classification = str(state.get("simulatedClassification", test_case.ground_truth.get("classification", "")))
        final_state = str(state.get("simulatedFinalState", test_case.ground_truth.get("finalState", "RESOLVED")))
        tool_calls = tuple(state.get("simulatedToolCalls", test_case.allowed_tools))
        expected_tool_args = test_case.ground_truth.get("expectedToolArgs", {})
        tool_call_args = state.get(
            "simulatedToolCallArgs", {name: expected_tool_args[name] for name in tool_calls if name in expected_tool_args},
        )
        return CaseExecutionResult(
            run_id=str(run_id), test_case_id=str(test_case.test_case_id), run_generation=run_generation,
            final_state=final_state, tool_calls=tool_calls, classification=classification,
            policy_violation_count=int(state.get("simulatedPolicyViolationCount", 0)),
            forbidden_tool_call_count=int(state.get("simulatedForbiddenToolCallCount", 0)),
            unauthorized_memory_access_count=int(state.get("simulatedUnauthorizedMemoryAccessCount", 0)),
            cost_tokens=100, latency_ms=500, workflow_trace_ref=f"fake-trace-{run_id}",
            approval_triggered=bool(state.get("simulatedApprovalTriggered", test_case.required_approval)),
            verification_passed=bool(state.get("simulatedVerificationPassed", True)), tool_call_args=tool_call_args,
            explanation_text=str(state.get(
                "simulatedExplanationText",
                f"Diagnosed {classification} for scenario '{test_case.scenario}' and reached final state {final_state} "
                f"using {', '.join(tool_calls) or 'no tools'}.",
            )),
        )


class AgentRuntimeEvaluationUnavailableError(RuntimeError):
    """SPEC-EI-012: raised for every failure mode of the real HTTP call (timeout,
    connection refused, non-2xx status, malformed response body) — a single type so
    ExecuteCaseService's own `except Exception` boundary (see that module's own
    docstring: "any runner failure becomes a FAILED case, never a stuck run") keeps
    working unchanged for this adapter exactly as it already does for
    FakeAgentRuntimeEvaluationAdapter's own RuntimeError.
    """


class HttpAgentRuntimeEvaluationAdapter:
    """SPEC-EI-012 (agent-runtime-evaluation-client-contract): the real client for
    03-agent-runtime-orchestration's own evaluation endpoint contract —
    `POST {base_url}/agent-runtime/evaluation/execute-case`. 03 does not expose this
    endpoint yet (no paired spec on that domain's own roadmap creates it); this
    adapter is still the honest "real" half of the contract SPEC-EI-012 owns — the
    request/response shape, timeout, and error taxonomy 03 will need to match — tested
    here against a mock transport (14-testing-strategy §"Agent Runtime evaluation
    contract 有 mock/integration 测试"), not a live 03 instance.

    Deliberately never sends `test_case.ground_truth`: this call asks the system
    under test to *attempt* the case, the same information a real support agent would
    have — leaking the answer key would make every real integration test meaningless,
    not just this fake one (contrast FakeAgentRuntimeEvaluationAdapter, which reads
    ground truth only because it plays the agent and the grader's own answer key at
    once, a shortcut this real adapter must never take).
    """

    def __init__(self, client: httpx.Client, base_url: str) -> None:
        self._client = client
        self._base_url = base_url.rstrip("/")

    def execute_case(self, run_id: RunId, target_version: str, test_case: EvaluationTestCase, run_generation: int) -> CaseExecutionResult:
        payload = {
            "runId": str(run_id),
            "runGeneration": run_generation,
            "targetVersion": target_version,
            "testCaseId": str(test_case.test_case_id),
            "caseKey": test_case.case_key,
            "scenario": test_case.scenario,
            "userRequestRedacted": test_case.user_request_redacted,
            "mockSystemState": test_case.mock_system_state,
            "allowedTools": list(test_case.allowed_tools),
            "forbiddenTools": list(test_case.forbidden_tools),
            "requiredApproval": test_case.required_approval,
            "verificationCondition": test_case.verification_condition,
        }
        try:
            response = self._client.post(f"{self._base_url}/agent-runtime/evaluation/execute-case", json=payload)
            response.raise_for_status()
            body = response.json()
            return CaseExecutionResult(
                run_id=str(run_id), test_case_id=str(test_case.test_case_id), run_generation=run_generation,
                final_state=str(body["finalState"]), tool_calls=tuple(body.get("toolCalls", ())),
                classification=str(body["classification"]), policy_violation_count=int(body.get("policyViolationCount", 0)),
                forbidden_tool_call_count=int(body.get("forbiddenToolCallCount", 0)),
                unauthorized_memory_access_count=int(body.get("unauthorizedMemoryAccessCount", 0)),
                cost_tokens=int(body.get("costTokens", 0)), latency_ms=int(body.get("latencyMs", 0)),
                workflow_trace_ref=str(body.get("workflowTraceRef", "")), status=CaseExecutionStatus.COMPLETED,
                approval_triggered=bool(body.get("approvalTriggered", False)),
                verification_passed=bool(body.get("verificationPassed", True)),
                tool_call_args=dict(body.get("toolCallArgs", {})),
                explanation_text=str(body.get("explanationText", "")),
            )
        except httpx.TimeoutException as exc:
            raise AgentRuntimeEvaluationUnavailableError(f"agent runtime evaluation call timed out for run {run_id}") from exc
        except httpx.HTTPStatusError as exc:
            raise AgentRuntimeEvaluationUnavailableError(
                f"agent runtime evaluation call for run {run_id} returned {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise AgentRuntimeEvaluationUnavailableError(f"agent runtime evaluation call failed for run {run_id}: {exc}") from exc
        except (KeyError, TypeError, ValueError) as exc:
            # response.json() raising (malformed body) is a ValueError subclass;
            # a well-formed body missing a required key/typed wrong lands here too —
            # both are "the response was malformed," not a transport failure.
            raise AgentRuntimeEvaluationUnavailableError(
                f"agent runtime evaluation response for run {run_id} was malformed: {exc}"
            ) from exc
