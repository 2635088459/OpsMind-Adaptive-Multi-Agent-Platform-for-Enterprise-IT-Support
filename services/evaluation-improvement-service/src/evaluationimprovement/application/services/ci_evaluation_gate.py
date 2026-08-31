"""13-package-and-class-design §"应用层" (pragmatic extension — not among the 10
named services, added the same way audit_query.py/grader_catalog.py were):
CiEvaluationGateService. SPEC-EI-022 (ci-evaluation-gate-harness) 04-use-cases
UC-EI-002/UC-EI-003 end to end, in one call: create the run, drive
CaseRunnerService-claimable work to completion, score every completed case,
finalize/compare/evaluate the gate, and return one `passed: bool` a CI job's exit
code can read directly. Composes other services entirely through their own
application.ports_in Protocols (never their concrete classes) — the same seam
interfaces.rest.router already depends on, just consumed from another application
service instead of a controller.
"""

from __future__ import annotations

from evaluationimprovement.application.commands import (
    CompareRegressionCommand,
    CreateRunCommand,
    EvaluateReleaseGateCommand,
    FinalizeRunScoringCommand,
    RunCiGateCommand,
    ScoreCaseCommand,
)
from evaluationimprovement.application.ports_in import (
    CaseRunnerPort,
    CompareRegressionUseCase,
    CreateRunUseCase,
    EvaluateReleaseGateUseCase,
    ReportQueryUseCase,
    ScoreRunUseCase,
)
from evaluationimprovement.application.ports_out import CaseExecutionResultRepository, TestCaseRepository
from evaluationimprovement.application.views import CiGateOutcome
from evaluationimprovement.domain.enums import CaseExecutionStatus

# 09-concurrency-and-idempotency: a resubmitted run_key must return the *same*
# outcome, never re-drive an already-scored/already-terminal run through
# score_case()/compare()/evaluate() a second time (each of those refuses a run that
# has already moved past the status they each require).
_HAS_A_GATE_REPORT = frozenset({"PASSED", "FAILED"})
_ALREADY_PAST_EXECUTION = frozenset({"SCORING", "COMPARING", "PASSED", "FAILED", "PARTIAL", "CANCELLED"})


class CiEvaluationGateService:
    def __init__(
        self, create_run_port: CreateRunUseCase, case_runner_port: CaseRunnerPort, score_run_port: ScoreRunUseCase,
        compare_regression_port: CompareRegressionUseCase, evaluate_release_gate_port: EvaluateReleaseGateUseCase,
        report_query_port: ReportQueryUseCase, test_case_repository: TestCaseRepository,
        case_execution_result_repository: CaseExecutionResultRepository,
    ) -> None:
        self._create_run_port = create_run_port
        self._case_runner_port = case_runner_port
        self._score_run_port = score_run_port
        self._compare_regression_port = compare_regression_port
        self._evaluate_release_gate_port = evaluate_release_gate_port
        self._report_query_port = report_query_port
        self._test_case_repository = test_case_repository
        self._case_execution_result_repository = case_execution_result_repository

    def run_gate(self, command: RunCiGateCommand) -> CiGateOutcome:
        run = self._create_run_port.create_run(CreateRunCommand(
            run_key=command.run_key, dataset_id=command.dataset_id, target_version=command.target_version,
            baseline_version=command.baseline_version, grader_bundle_version=command.grader_bundle_version,
            policy_version=command.policy_version, gate_policy=command.gate_policy, triggered_by=command.triggered_by,
            actor=command.actor, correlation_id=command.correlation_id,
        ))

        if run.status.value in _HAS_A_GATE_REPORT:
            report = self._report_query_port.find_report_for_run(run.run_id)
            return CiGateOutcome(
                run_id=run.run_id, run_status=run.status.value, gate_decision=report.overall_decision.value,
                critical_failures=report.critical_failures, reason=report.recommendation,
                passed=run.status.value == "PASSED",
            )
        if run.status.value in _ALREADY_PAST_EXECUTION:
            # PARTIAL/CANCELLED/SCORING/COMPARING — an earlier call with this same
            # run_key already drove this run past where a fresh drive loop could
            # safely resume; resubmit with a new run_key for a fresh benchmark.
            return CiGateOutcome(
                run_id=run.run_id, run_status=run.status.value, gate_decision=None, critical_failures=(),
                reason=f"run is already {run.status.value} from an earlier call with this run_key — resubmit with a new run_key to start a fresh benchmark",
                passed=False,
            )

        # SPEC-EI-011: drives whatever create_run() already enqueued — the same
        # claim/retry loop CaseRunnerWorker runs standalone, just bounded here so a CI
        # job cannot hang forever on a case stuck in retry backoff. A pass that claims
        # nothing means either everything is done or the rest is not due yet; either
        # way, further iterations right now would not help.
        for _ in range(command.max_iterations):
            self._case_runner_port.reclaim_expired_leases(command.batch_size)
            report = self._case_runner_port.run_once(f"ci-gate-{run.run_id}", command.batch_size)
            if report.claimed == 0:
                break

        # SPEC-EI-011/014: CaseRunnerService only drives execution, never scoring
        # (scoring can mean real LLM Judge calls with their own cost/pacing concerns —
        # a deliberately separate step, not an implicit side effect of execution). The
        # harness scores every case that actually completed; FAILED/SKIPPED cases are
        # left for finalize_scoring()'s own completeness check to account for.
        for test_case in self._test_case_repository.find_by_dataset(command.dataset_id):
            result = self._case_execution_result_repository.find(run.run_id, test_case.test_case_id)
            if result is not None and result.status == CaseExecutionStatus.COMPLETED:
                self._score_run_port.score_case(ScoreCaseCommand(
                    run_id=run.run_id, test_case_id=test_case.test_case_id, run_generation=result.run_generation,
                    actor=command.actor, correlation_id=command.correlation_id,
                ))

        try:
            finalized = self._score_run_port.finalize_scoring(FinalizeRunScoringCommand(
                run_id=run.run_id, actor=command.actor, correlation_id=command.correlation_id,
            ))
        except ValueError as exc:
            # The run never left QUEUED (an empty dataset — no case ever called
            # execute_case to drive it to RUNNING) or a case is still genuinely
            # in-flight past `max_iterations` — either way, not a release-gate
            # decision this call can make.
            return CiGateOutcome(
                run_id=run.run_id, run_status=run.status.value, gate_decision=None, critical_failures=(),
                reason=f"run could not be finalized within max_iterations={command.max_iterations}: {exc}", passed=False,
            )

        if finalized.status.value == "PARTIAL":
            # 10-failure-handling §"Partial Run": "Run 可进入 PARTIAL，但 release gate 不能
            # passed" — never even attempted below.
            return CiGateOutcome(
                run_id=run.run_id, run_status="PARTIAL", gate_decision=None, critical_failures=(),
                reason="run finalized as PARTIAL: one or more cases never completed; the release gate cannot evaluate an incomplete result set",
                passed=False,
            )

        self._compare_regression_port.compare(CompareRegressionCommand(
            run_id=run.run_id, baseline_run_id=command.baseline_run_id, actor=command.actor,
            correlation_id=command.correlation_id,
        ))
        gate_report = self._evaluate_release_gate_port.evaluate(EvaluateReleaseGateCommand(
            run_id=run.run_id, gate_policy=command.gate_policy, actor=command.actor, correlation_id=command.correlation_id,
        ))
        passed = gate_report.overall_decision.value == "PASSED"
        return CiGateOutcome(
            run_id=run.run_id, run_status=("PASSED" if passed else "FAILED"), gate_decision=gate_report.overall_decision.value,
            critical_failures=gate_report.critical_failures,
            reason=gate_report.recommendation, passed=passed,
        )
