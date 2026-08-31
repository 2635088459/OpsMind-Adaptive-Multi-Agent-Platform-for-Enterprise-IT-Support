"""13-package-and-class-design §"应用层" (pragmatic extension — see
application.ports_out.CaseExecutionQueueRepository's own docstring): CaseRunnerService,
the sole implementation of CaseRunnerPort. SPEC-EI-011 (case-runner-worker-lease-retry)
04-use-cases UC-EI-002 step 3: "Runner 调用 Agent Runtime 的 evaluation endpoint，以 mock
system state 执行 case" — this is the actual runner loop that call describes.
interfaces.rest.router's own "Pipeline steps" comment named this exact seam: "these
admin-triggered endpoints are the seam a future runner/worker spec will call instead of
a human" — CaseRunnerService is that caller. It never re-implements ExecuteCaseService's
own run-state-machine/result-recording rules, only claims work and decides retry-vs-
exhausted around a single execute_case() call per claimed entry.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

from evaluationimprovement.application.commands import ExecuteCaseCommand
from evaluationimprovement.application.exceptions import RunNotFoundException, TestCaseNotFoundException
from evaluationimprovement.application.ports_in import ExecuteCaseUseCase
from evaluationimprovement.application.ports_out import (
    CaseExecutionQueueRepository,
    CaseExecutionResultRepository,
    ClockPort,
)
from evaluationimprovement.application.records import CaseExecutionResult
from evaluationimprovement.application.views import CaseRunnerReport
from evaluationimprovement.domain.enums import CaseExecutionStatus
from evaluationimprovement.domain.ids import RunId, TestCaseId

# 10-failure-handling names no fixed retry count/backoff — mirrors
# dispatch_outbox_events.py's own _MAX_ATTEMPTS_BEFORE_DEAD_LETTER/
# _BACKOFF_BASE_SECONDS constants exactly (same exponential-backoff shape, applied to
# case execution instead of event publication).
_MAX_ATTEMPTS = 5
_BACKOFF_BASE_SECONDS = 30
# A case execution attempt calls out to Agent Runtime, which itself runs an LLM-driven
# agent workflow — a lease needs enough headroom to outlast a slow-but-healthy attempt,
# not just a network round trip (contrast tool-integration-gateway's own much shorter
# connector-invoke timeout). Overridable via Settings.case_runner_lease_seconds
# (container.py threads it through — this layer must not import settings itself, see
# ports_out module docstring's own "Application must not depend on infrastructure").
_DEFAULT_LEASE_SECONDS = 300


class CaseRunnerService:
    def __init__(
        self, case_execution_queue_repository: CaseExecutionQueueRepository,
        case_execution_result_repository: CaseExecutionResultRepository, execute_case_port: ExecuteCaseUseCase,
        clock: ClockPort, lease_seconds: int = _DEFAULT_LEASE_SECONDS,
    ) -> None:
        self._case_execution_queue_repository = case_execution_queue_repository
        self._case_execution_result_repository = case_execution_result_repository
        self._execute_case_port = execute_case_port
        self._clock = clock
        self._lease_seconds = lease_seconds

    def run_once(self, worker_id: str, batch_size: int = 10) -> CaseRunnerReport:
        """Claims up to `batch_size` due entries and drives one execution attempt
        each. A claim lost to another worker (claim() returns False) is silently
        skipped, never counted or retried by this call — the entry is still PENDING
        and some worker's next poll will pick it up.
        """
        now = self._clock.now()
        candidates = self._case_execution_queue_repository.find_claimable(now, batch_size)
        claimed = completed = retried = exhausted = 0
        for entry in candidates:
            run_id = RunId(uuid.UUID(entry.run_id))
            test_case_id = TestCaseId(uuid.UUID(entry.test_case_id))
            lease_expires_at = now + timedelta(seconds=self._lease_seconds)
            if not self._case_execution_queue_repository.claim(run_id, test_case_id, worker_id, now, lease_expires_at):
                continue
            claimed += 1

            attempt_count = entry.attempt_count + 1
            try:
                self._execute_case_port.execute_case(ExecuteCaseCommand(
                    run_id=run_id, test_case_id=test_case_id, attempt=attempt_count,
                    actor=f"case-runner-worker:{worker_id}", correlation_id=str(uuid.uuid4()),
                ))
            except (RunNotFoundException, TestCaseNotFoundException, ValueError):
                # The run/test case no longer exists, or the run left QUEUED/RUNNING
                # (cancelled, or already finalized by another path) — never retryable,
                # and ExecuteCaseService never got far enough to record a result. Give
                # up on this entry rather than spin on it forever.
                self._case_execution_queue_repository.mark_done(run_id, test_case_id)
                continue

            result = self._case_execution_result_repository.find(run_id, test_case_id)
            if result is not None and result.status == CaseExecutionStatus.FAILED:
                if attempt_count >= _MAX_ATTEMPTS:
                    self._case_execution_queue_repository.mark_exhausted(run_id, test_case_id, attempt_count)
                    exhausted += 1
                else:
                    next_attempt_at = self._clock.now() + _backoff(attempt_count)
                    self._case_execution_queue_repository.mark_retry(run_id, test_case_id, next_attempt_at, attempt_count)
                    retried += 1
            else:
                # COMPLETED — ExecuteCaseService never produces a SKIPPED result
                # (that is skip_case()'s own, separate command), so this is the only
                # other reachable outcome of a successful execute_case() call.
                self._case_execution_queue_repository.mark_done(run_id, test_case_id)
                completed += 1

        return CaseRunnerReport(claimed=claimed, completed=completed, retried=retried, exhausted=exhausted)

    def reclaim_expired_leases(self, batch_size: int = 50) -> int:
        """09-concurrency-and-idempotency precedent (tool-integration-gateway's own
        SPEC-TG-010 "Worker Concurrent Claim": "Other workers may take over after
        lease expiry") applied to this domain's own case queue: a worker that claimed
        an entry and then crashed or was killed mid-attempt leaves it LEASED forever
        unless some worker's own reclaim pass resets it. Returns the number of leases
        actually reclaimed by this call.
        """
        now = self._clock.now()
        expired = self._case_execution_queue_repository.find_expired_leases(now, batch_size)
        reclaimed = 0
        for entry in expired:
            run_id = RunId(uuid.UUID(entry.run_id))
            test_case_id = TestCaseId(uuid.UUID(entry.test_case_id))
            attempt_count = entry.attempt_count + 1
            next_attempt_at = self._clock.now() + _backoff(attempt_count)
            if not self._case_execution_queue_repository.release_expired_lease(run_id, test_case_id, next_attempt_at, attempt_count):
                continue
            reclaimed += 1

            if attempt_count >= _MAX_ATTEMPTS:
                self._case_execution_queue_repository.mark_exhausted(run_id, test_case_id, attempt_count)
                # 10-failure-handling §"Partial Run": "未执行 case" must still be
                # accounted for. A lease that expired before the crashed worker ever
                # reached ExecuteCaseService's own save() call leaves no
                # CaseExecutionResult at all — without this, finalize_scoring() would
                # raise IncompleteRunException for this case forever, since nothing
                # else in this domain ever produces one for an attempt that never
                # actually ran.
                if self._case_execution_result_repository.find(run_id, test_case_id) is None:
                    self._case_execution_result_repository.save(_orphaned_result(run_id, test_case_id, entry.run_generation))
        return reclaimed


def _backoff(attempt_count: int) -> timedelta:
    return timedelta(seconds=_BACKOFF_BASE_SECONDS * (2 ** (attempt_count - 1)))


def _orphaned_result(run_id: RunId, test_case_id: TestCaseId, run_generation: int) -> CaseExecutionResult:
    return CaseExecutionResult(
        run_id=str(run_id), test_case_id=str(test_case_id), run_generation=run_generation,
        final_state="", tool_calls=(), classification="", policy_violation_count=0, forbidden_tool_call_count=0,
        unauthorized_memory_access_count=0, cost_tokens=0, latency_ms=0, workflow_trace_ref="",
        status=CaseExecutionStatus.FAILED, failure_reason="case runner worker lease expired before any attempt completed",
    )
