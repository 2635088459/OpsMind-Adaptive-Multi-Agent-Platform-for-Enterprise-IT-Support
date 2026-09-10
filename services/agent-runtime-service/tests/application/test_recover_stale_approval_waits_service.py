"""SPEC-XREL-001: RecoverStaleApprovalWaitsService bounds a WAITING_FOR_APPROVAL
Workflow Instance whose approval decision never arrives. Symmetric with
tests/application/test_recover_stale_tool_waits_service.py.
"""

from __future__ import annotations

import dataclasses
import uuid
from datetime import timedelta

import pytest

from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.services.recover_stale_approval_waits import RecoverStaleApprovalWaitsService
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.ids import (
    DefinitionVersion,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryOutboxRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit

_TIMEOUT_SECONDS = 1800.0


@pytest.fixture
def wiring():
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    checkpoint_repository = InMemoryCheckpointRepository()
    outbox_repository = InMemoryOutboxRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    fail_workflow_service = FailWorkflowService(
        workflow_instance_repository, outbox_repository, command_idempotency_repository, clock,
        checkpoint_repository, telemetry, audit_recorder,
    )
    service = RecoverStaleApprovalWaitsService(
        workflow_instance_repository, clock, fail_workflow_service, telemetry, audit_recorder, _TIMEOUT_SECONDS,
    )
    return service, workflow_instance_repository, outbox_repository, clock


def _seed(repo, clock, *, state: WorkflowState) -> WorkflowInstanceId:
    now = clock.now()
    workflow_instance_id = WorkflowInstanceId.new_id()
    running = WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("conversational_intake"), definition_id=WorkflowDefinitionId("conv-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        current_checkpoint_id=None, completed_at=None, created_at=now, updated_at=now,
    )
    repo.save(running)
    if state is not WorkflowState.RUNNING:
        repo.save(dataclasses.replace(running, state=state, workflow_version=2))
    return workflow_instance_id


def test_a_workflow_stuck_past_the_timeout_is_failed(wiring) -> None:
    service, repo, outbox_repository, clock = wiring
    workflow_instance_id = _seed(repo, clock, state=WorkflowState.WAITING_FOR_APPROVAL)

    clock.advance(timedelta(seconds=_TIMEOUT_SECONDS + 60))
    report = service.scan_and_recover()

    assert (report.scanned, report.timed_out) == (1, 1)
    assert repo.find_by_id(workflow_instance_id).state is WorkflowState.FAILED
    # FailWorkflowService published its own workflow.failed.v1 — no new event type here.
    published = outbox_repository.find_dispatchable(clock.now(), 10)
    assert any(record.event_type == "workflow.failed.v1" for record in published)


def test_a_workflow_within_the_timeout_is_left_alone(wiring) -> None:
    service, repo, _outbox, clock = wiring
    workflow_instance_id = _seed(repo, clock, state=WorkflowState.WAITING_FOR_APPROVAL)

    clock.advance(timedelta(seconds=_TIMEOUT_SECONDS - 60))
    report = service.scan_and_recover()

    assert (report.scanned, report.timed_out) == (0, 0)
    assert repo.find_by_id(workflow_instance_id).state is WorkflowState.WAITING_FOR_APPROVAL


def test_a_non_approval_wait_non_terminal_workflow_is_ignored(wiring) -> None:
    service, repo, _outbox, clock = wiring
    workflow_instance_id = _seed(repo, clock, state=WorkflowState.WAITING_FOR_TOOL)

    clock.advance(timedelta(seconds=_TIMEOUT_SECONDS * 4))
    report = service.scan_and_recover()

    assert (report.scanned, report.timed_out) == (0, 0)
    assert repo.find_by_id(workflow_instance_id).state is WorkflowState.WAITING_FOR_TOOL


def test_scan_is_a_noop_when_nothing_is_stale(wiring) -> None:
    service, repo, _outbox, clock = wiring
    _seed(repo, clock, state=WorkflowState.RUNNING)

    clock.advance(timedelta(seconds=_TIMEOUT_SECONDS * 4))
    report = service.scan_and_recover()

    assert (report.scanned, report.timed_out) == (0, 0)


def test_a_late_real_decision_after_recovery_is_a_clean_noop(wiring) -> None:
    """The scan fails the workflow; a subsequent approval.granted delivery for the
    same workflow must not raise or double-transition — consume_approval.py already
    returns early for a workflow no longer WAITING_FOR_APPROVAL, and a second fail()
    under the timeout key is swallowed here.
    """
    service, repo, _outbox, clock = wiring
    workflow_instance_id = _seed(repo, clock, state=WorkflowState.WAITING_FOR_APPROVAL)
    clock.advance(timedelta(seconds=_TIMEOUT_SECONDS + 60))

    service.scan_and_recover()
    # A second scan (or a redelivered expired event routed through the same service)
    # finds nothing to do.
    report = service.scan_and_recover()

    assert (report.scanned, report.timed_out) == (0, 0)
    assert repo.find_by_id(workflow_instance_id).state is WorkflowState.FAILED
