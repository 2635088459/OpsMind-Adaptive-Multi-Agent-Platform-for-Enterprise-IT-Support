"""13-package-and-class-design §"应用层": ExecuteCaseService, the sole implementation
of ExecuteCaseUseCase. 04-use-cases UC-EI-002 step 3: "Runner 调用 Agent Runtime 的
evaluation endpoint，以 mock system state 执行 case." SPEC-EI-009 adds the case-level
state machine 10-failure-handling §"Partial Run" names: a runner error must never
leave a case (and therefore its whole run) permanently stuck in SCORING — see
domain.enums.CaseExecutionStatus's own docstring.
"""

from __future__ import annotations

from opentelemetry import trace

from evaluationimprovement.application.commands import ExecuteCaseCommand, SkipCaseCommand
from evaluationimprovement.application.exceptions import RunNotFoundException, TestCaseNotFoundException
from evaluationimprovement.application.ports_out import (
    AgentRuntimeEvaluationPort,
    CaseExecutionResultRepository,
    EvaluationRunRepository,
    TestCaseRepository,
)
from evaluationimprovement.application.records import CaseExecutionResult
from evaluationimprovement.domain.enums import CaseExecutionStatus, RunStatus

tracer = trace.get_tracer(__name__)


class ExecuteCaseService:
    def __init__(
        self, run_repository: EvaluationRunRepository, test_case_repository: TestCaseRepository,
        case_execution_result_repository: CaseExecutionResultRepository, agent_runtime_port: AgentRuntimeEvaluationPort,
    ) -> None:
        self._run_repository = run_repository
        self._test_case_repository = test_case_repository
        self._case_execution_result_repository = case_execution_result_repository
        self._agent_runtime_port = agent_runtime_port

    def execute_case(self, command: ExecuteCaseCommand) -> None:
        """The first execute_case() call for a run transitions it QUEUED -> RUNNING;
        later calls against an already-RUNNING run are a no-op transition. 09-
        concurrency-and-idempotency §"并发规则": "同一个 run 的同一个 case 可以重试" — a
        later attempt for the same test_case_id simply overwrites the stored result
        (InMemoryCaseExecutionResultRepository.save() is keyed by (run_id,
        test_case_id), not by attempt). SPEC-EI-009: a runner exception is caught and
        recorded as a FAILED CaseExecutionResult rather than propagated — an earlier
        version of this method let the exception propagate unhandled, which left the
        case permanently unaccounted-for (finalize_scoring() would then block the run
        in SCORING forever, since score_case() could never be called for a case that
        was never actually saved).
        """
        with tracer.start_as_current_span("CaseRunner.executeCase"):
            self._execute_case_traced(command)

    def _execute_case_traced(self, command: ExecuteCaseCommand) -> None:
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status == RunStatus.QUEUED:
            run = run.start()
            self._run_repository.save(run, expected_status=RunStatus.QUEUED)
        elif run.status != RunStatus.RUNNING:
            raise ValueError(f"run {command.run_id} is {run.status} and cannot execute cases")

        test_case = self._test_case_repository.find_by_id(command.test_case_id)
        if test_case is None:
            raise TestCaseNotFoundException(command.test_case_id)

        run_generation = self._run_repository.current_generation(command.run_id)
        try:
            result = self._agent_runtime_port.execute_case(
                command.run_id, run.version_binding.target_version, test_case, run_generation,
            )
        except Exception as exc:  # noqa: BLE001 — an external-port boundary: any runner failure becomes a FAILED case, never a stuck run.
            result = _failed_result(command.run_id, command.test_case_id, run_generation, str(exc))
        self._case_execution_result_repository.save(result)

    def skip_case(self, command: SkipCaseCommand) -> None:
        """SPEC-EI-009: explicitly marks a case as never-to-be-executed (the "未执行
        case" 10-failure-handling's own Partial Run report must list), so
        finalize_scoring() can account for it without a runner ever having been
        called. Only legal while the run is still accepting case activity.
        """
        run = self._run_repository.find_by_id(command.run_id)
        if run is None:
            raise RunNotFoundException(command.run_id)
        if run.status == RunStatus.QUEUED:
            run = run.start()
            self._run_repository.save(run, expected_status=RunStatus.QUEUED)
        elif run.status != RunStatus.RUNNING:
            raise ValueError(f"run {command.run_id} is {run.status} and cannot skip cases")

        test_case = self._test_case_repository.find_by_id(command.test_case_id)
        if test_case is None:
            raise TestCaseNotFoundException(command.test_case_id)

        run_generation = self._run_repository.current_generation(command.run_id)
        result = CaseExecutionResult(
            run_id=str(command.run_id), test_case_id=str(command.test_case_id), run_generation=run_generation,
            final_state="", tool_calls=(), classification="", policy_violation_count=0, forbidden_tool_call_count=0,
            unauthorized_memory_access_count=0, cost_tokens=0, latency_ms=0, workflow_trace_ref="",
            status=CaseExecutionStatus.SKIPPED, failure_reason=command.reason,
        )
        self._case_execution_result_repository.save(result)


def _failed_result(run_id, test_case_id, run_generation: int, failure_reason: str) -> CaseExecutionResult:  # noqa: ANN001
    return CaseExecutionResult(
        run_id=str(run_id), test_case_id=str(test_case_id), run_generation=run_generation,
        final_state="", tool_calls=(), classification="", policy_violation_count=0, forbidden_tool_call_count=0,
        unauthorized_memory_access_count=0, cost_tokens=0, latency_ms=0, workflow_trace_ref="",
        status=CaseExecutionStatus.FAILED, failure_reason=failure_reason,
    )
