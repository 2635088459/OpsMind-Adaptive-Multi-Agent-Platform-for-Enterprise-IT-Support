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
from evaluationimprovement.application.outbox_codec import build_outbox_record
from evaluationimprovement.application.services.dispatch_outbox_events import DispatchOutboxEventsService
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.events import EvaluationRunRequested
from evaluationimprovement.domain.ids import CorrelationId, RunId


@pytest.mark.unit
def test_dispatch_publishes_the_run_requested_event(container: Container) -> None:
    """08-transaction-and-outbox §"事务原则": create_run appends
    evaluation.run.requested.v1 to the outbox; dispatch_due_events() is the only
    thing that hands it to EventPublisherPort.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "X"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))

    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    container.create_run_service.create_run(CreateRunCommand(
        run_key="dispatch-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))

    report = container.dispatch_outbox_events_service.dispatch_due_events(batch_size=10)
    assert report.dispatched == 1
    assert report.failed == 0
    assert report.dead_lettered == 0

    # A second dispatch pass finds nothing left PENDING.
    empty_report = container.dispatch_outbox_events_service.dispatch_due_events(batch_size=10)
    assert empty_report.dispatched == 0


class _FakeClock:
    def __init__(self, start):  # noqa: ANN001
        self._now = start

    def now(self):  # noqa: ANN201
        return self._now

    def advance(self, delta) -> None:  # noqa: ANN001
        self._now = self._now + delta


class _AlwaysFailingPublisher:
    def publish(self, record) -> bool:  # noqa: ANN001, ARG002
        return False


@pytest.mark.unit
def test_publish_failure_backs_off_then_dead_letters(container: Container) -> None:
    clock = _FakeClock(container.clock.now())
    service = DispatchOutboxEventsService(container.outbox_repository, _AlwaysFailingPublisher(), clock)

    now = clock.now()
    container.outbox_repository.append(build_outbox_record(
        EvaluationRunRequested(run_id=RunId.new_id(), run_key="rk", occurred_at=now), "evaluation.run.requested.v1",
        aggregate_id="rk", occurred_at=now, correlation_id=CorrelationId.new_id(),
    ))
    # _MAX_ATTEMPTS_BEFORE_DEAD_LETTER = 5: each of the first 4 failures backs off
    # (30s * 2**(attempt-1)); advancing the fake clock past the longest possible
    # backoff before each retry guarantees the row is dispatchable again every time.
    for _ in range(5):
        clock.advance(timedelta(hours=1))
        service.dispatch_due_events(batch_size=10)

    dead_letters = container.outbox_repository.find_dead_letter(10)
    assert len(dead_letters) == 1


@pytest.mark.unit
def test_admin_recovery_service_dispatches_and_audits(container: Container) -> None:
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling:
    "admin repair/replay API 有审计" — unlike DispatchOutboxEventsService's own plain
    dispatch_due_events(), the admin-triggered wrapper writes an audit record.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="admin-recovery-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "X"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))
    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    container.create_run_service.create_run(CreateRunCommand(
        run_key="admin-recovery-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))

    before = len(container.audit_record_query_service.list_audit_events(1000))
    report = container.admin_recovery_service.dispatch_outbox_events(batch_size=10, actor="admin-1", correlation_id="corr-recovery-1")
    assert report.dispatched == 1

    after = container.audit_record_query_service.list_audit_events(1000)
    assert len(after) == before + 1
    assert after[0].action == "admin_dispatch_outbox_events"
    assert after[0].actor == "admin-1"
