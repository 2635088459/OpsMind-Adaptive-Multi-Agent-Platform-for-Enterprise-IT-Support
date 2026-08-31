"""SPEC-EI-011 (case-runner-worker-lease-retry): CaseRunnerService — claim, retry
with backoff, exhaustion, and expired-lease reclaim. Mirrors
test_dispatch_outbox_events_service.py's own `_FakeClock` pattern exactly: a fresh
CaseRunnerService is constructed directly against the container's own (shared)
repositories/execute_case_service but with an independently-advanceable clock, since
CreateRunService's own auto-enqueue at create_run() time already uses the container's
real clock.
"""

from __future__ import annotations

from datetime import timedelta

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    CreateRunCommand,
    PublishDatasetCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.services.run_case_queue import CaseRunnerService
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import CaseExecutionStatus, CaseQueueStatus, Criticality


class _FakeClock:
    def __init__(self, start):  # noqa: ANN001
        self._now = start

    def now(self):  # noqa: ANN201
        return self._now

    def advance(self, delta) -> None:  # noqa: ANN001
        self._now = self._now + delta


def _publish_dataset_with_case(container: Container, run_key: str, *, mock_system_state=None):
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=f"case-runner-dataset-{run_key}", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state=mock_system_state or {},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    added = container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key=run_key, dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))
    return run, added[0]


@pytest.mark.unit
def test_create_run_enqueues_every_dataset_test_case_exactly_once(container: Container) -> None:
    run, test_case = _publish_dataset_with_case(container, "case-runner-enqueue-001")

    entries = container.case_execution_queue_repository.find_by_run(run.run_id)
    assert len(entries) == 1
    assert entries[0].test_case_id == str(test_case.test_case_id)
    assert entries[0].status is CaseQueueStatus.PENDING
    assert entries[0].attempt_count == 0

    # 09-concurrency-and-idempotency: a resubmitted enqueue for a pair already queued
    # is a no-op — never a second row, never a reset of in-flight progress.
    container.case_execution_queue_repository.enqueue_many(
        run.run_id, (test_case.test_case_id,), 1, container.clock.now(),
    )
    assert len(container.case_execution_queue_repository.find_by_run(run.run_id)) == 1


@pytest.mark.unit
def test_run_once_claims_and_completes_a_successful_case(container: Container) -> None:
    run, test_case = _publish_dataset_with_case(container, "case-runner-success-001")
    service = CaseRunnerService(
        container.case_execution_queue_repository, container.case_execution_result_repository,
        container.execute_case_service, container.clock,
    )

    report = service.run_once("worker-1", batch_size=10)
    assert report.claimed == 1
    assert report.completed == 1
    assert report.retried == 0
    assert report.exhausted == 0

    entry = container.case_execution_queue_repository.find_by_run(run.run_id)[0]
    assert entry.status is CaseQueueStatus.DONE

    result = container.case_execution_result_repository.find(run.run_id, test_case.test_case_id)
    assert result is not None
    assert result.status is CaseExecutionStatus.COMPLETED

    # Nothing left claimable — a second pass is a genuine no-op, not an error.
    empty_report = service.run_once("worker-1", batch_size=10)
    assert empty_report.claimed == 0


@pytest.mark.unit
def test_run_once_retries_a_failing_case_with_backoff_then_exhausts(container: Container) -> None:
    run, test_case = _publish_dataset_with_case(
        container, "case-runner-exhaust-001", mock_system_state={"simulateRunnerError": "agent runtime timed out"},
    )
    clock = _FakeClock(container.clock.now())
    service = CaseRunnerService(
        container.case_execution_queue_repository, container.case_execution_result_repository,
        container.execute_case_service, clock,
    )

    # _MAX_ATTEMPTS = 5: each of the first 4 failures backs off (30s * 2**(attempt-1));
    # advancing the fake clock by a full hour before each pass guarantees the entry is
    # claimable again every time, mirroring
    # test_dispatch_outbox_events_service.py's own identical-shaped loop.
    reports = []
    for _ in range(5):
        clock.advance(timedelta(hours=1))
        reports.append(service.run_once("worker-1", batch_size=10))

    for report in reports[:4]:
        assert report.claimed == 1
        assert report.retried == 1
        assert report.exhausted == 0
    assert reports[4].claimed == 1
    assert reports[4].exhausted == 1

    entry = container.case_execution_queue_repository.find_by_run(run.run_id)[0]
    assert entry.status is CaseQueueStatus.EXHAUSTED
    assert entry.attempt_count == 5

    result = container.case_execution_result_repository.find(run.run_id, test_case.test_case_id)
    assert result is not None
    assert result.status is CaseExecutionStatus.FAILED
    assert result.failure_reason == "agent runtime timed out"


@pytest.mark.unit
def test_reclaim_expired_leases_resets_a_crashed_workers_claim(container: Container) -> None:
    run, test_case = _publish_dataset_with_case(container, "case-runner-reclaim-001")
    clock = _FakeClock(container.clock.now())
    service = CaseRunnerService(
        container.case_execution_queue_repository, container.case_execution_result_repository,
        container.execute_case_service, clock,
    )

    # Simulates a worker that claimed the lease and then crashed before ever calling
    # execute_case() — never goes through service.run_once() at all.
    claimed = container.case_execution_queue_repository.claim(
        run.run_id, test_case.test_case_id, "dead-worker", clock.now(), clock.now() + timedelta(seconds=60),
    )
    assert claimed is True

    # Not yet expired — nothing to reclaim.
    assert service.reclaim_expired_leases(batch_size=10) == 0

    clock.advance(timedelta(seconds=120))
    reclaimed = service.reclaim_expired_leases(batch_size=10)
    assert reclaimed == 1

    entry = container.case_execution_queue_repository.find_by_run(run.run_id)[0]
    assert entry.status is CaseQueueStatus.PENDING
    assert entry.attempt_count == 1
    assert entry.leased_by is None
    # Only 1 of 5 attempts spent — not exhausted, and no case ever actually ran, so no
    # CaseExecutionResult exists yet.
    assert container.case_execution_result_repository.find(run.run_id, test_case.test_case_id) is None


@pytest.mark.unit
def test_reclaim_expired_leases_exhausts_and_backfills_a_failed_result_when_never_executed(container: Container) -> None:
    """10-failure-handling §"Partial Run": a case whose every lease expired before a
    worker ever reached ExecuteCaseService's own save() call must still end up
    accounted for — otherwise finalize_scoring() would raise IncompleteRunException
    for it forever.
    """
    run, test_case = _publish_dataset_with_case(container, "case-runner-reclaim-exhaust-001")
    clock = _FakeClock(container.clock.now())
    service = CaseRunnerService(
        container.case_execution_queue_repository, container.case_execution_result_repository,
        container.execute_case_service, clock,
    )

    for _ in range(5):
        claimed = container.case_execution_queue_repository.claim(
            run.run_id, test_case.test_case_id, "dead-worker", clock.now(), clock.now() + timedelta(seconds=60),
        )
        assert claimed is True
        clock.advance(timedelta(seconds=120))
        service.reclaim_expired_leases(batch_size=10)

    entry = container.case_execution_queue_repository.find_by_run(run.run_id)[0]
    assert entry.status is CaseQueueStatus.EXHAUSTED
    assert entry.attempt_count == 5

    result = container.case_execution_result_repository.find(run.run_id, test_case.test_case_id)
    assert result is not None
    assert result.status is CaseExecutionStatus.FAILED
    assert result.failure_reason == "case runner worker lease expired before any attempt completed"
