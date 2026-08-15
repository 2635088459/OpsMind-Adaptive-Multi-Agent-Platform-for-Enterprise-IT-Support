from __future__ import annotations

import dataclasses
import logging
import uuid
from datetime import timedelta

import pytest

from agentruntime.application.commands import RetryAgentTaskCommand
from agentruntime.application.exceptions import AgentTaskNotFoundException
from agentruntime.application.records import AgentTaskRecord, WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.recover_expired_lease_tasks import RecoverExpiredLeaseTasksService
from agentruntime.domain.enums import AgentTaskState, WorkflowState
from agentruntime.domain.exceptions import InvalidAgentTaskTransitionException
from agentruntime.domain.ids import (
    AgentTaskId,
    DefinitionVersion,
    LeaseToken,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryAuditRecordRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    clock = FakeClock()
    telemetry, audit_recorder = build_telemetry_collaborators(clock)
    service = RecoverExpiredLeaseTasksService(agent_task_repository, workflow_instance_repository, clock, telemetry, audit_recorder)
    return service, agent_task_repository, workflow_instance_repository, clock


def _seed_workflow(workflow_instance_repository: InMemoryWorkflowInstanceRepository, clock: FakeClock) -> WorkflowInstanceRecord:
    # SPEC-ARO-036 12-observability §"日志": a real, resolvable workflow so
    # RecoverExpiredLeaseTasksService's own ticket_id/ticket_cycle_id lookup has
    # something genuine to find, not just its own defensive None fallback.
    now = clock.now()
    record = WorkflowInstanceRecord(
        id=WorkflowInstanceId.new_id(), ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        current_checkpoint_id=None, completed_at=None, created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(record)
    return record


def _seed_claimed_task(
    agent_task_repository: InMemoryAgentTaskRepository, clock: FakeClock, *,
    attempt: int, max_attempts: int, lease_expired: bool, state: AgentTaskState = AgentTaskState.CLAIMED,
    workflow_instance_id: WorkflowInstanceId | None = None,
) -> AgentTaskId:
    now = clock.now()
    lease_expires_at = now - timedelta(seconds=1) if lease_expired else now + timedelta(minutes=5)
    agent_task_id = AgentTaskId.new_id()
    agent_task_repository.save(AgentTaskRecord(
        id=agent_task_id, workflow_instance_id=workflow_instance_id or WorkflowInstanceId.new_id(),
        task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=state, task_version=1, worker_id="worker-1",
        lease_token=LeaseToken.new_token(), lease_expires_at=lease_expires_at, result_payload=None, failure_reason=None,
        pause_generation=0, created_at=now, updated_at=now, attempt=attempt, max_attempts=max_attempts,
    ))
    return agent_task_id


def test_scan_and_recover_ignores_tasks_with_an_unexpired_lease(wiring) -> None:
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=False)

    report = service.scan_and_recover(batch_size=50)

    assert report.scanned == 0
    assert report.retried == 0
    assert report.staled == 0


def test_scan_and_recover_retries_a_task_with_attempts_remaining(wiring) -> None:
    """SPEC-ARO-029 10-failure-handling §"Runtime 崩溃后怎么恢复" step 5, the retry half."""
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    agent_task_id = _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=True)

    report = service.scan_and_recover(batch_size=50)

    assert report.scanned == 1
    assert report.retried == 1
    assert report.staled == 0
    recovered = agent_task_repository.find_by_id(agent_task_id)
    assert recovered.state is AgentTaskState.READY
    assert recovered.attempt == 2
    assert recovered.task_version == 2
    assert recovered.lease_expires_at is None


def test_scan_and_recover_marks_stale_when_retry_attempts_are_exhausted(wiring) -> None:
    """Confirmed via AskUserQuestion: exhausted retries land in STALE, reusing
    domain.agent_task.mark_stale() unchanged — not FAILED_FINAL.
    """
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    agent_task_id = _seed_claimed_task(agent_task_repository, clock, attempt=3, max_attempts=3, lease_expired=True)

    report = service.scan_and_recover(batch_size=50)

    assert report.scanned == 1
    assert report.retried == 0
    assert report.staled == 1
    recovered = agent_task_repository.find_by_id(agent_task_id)
    assert recovered.state is AgentTaskState.STALE
    assert recovered.attempt == 3
    assert recovered.lease_expires_at is None
    # worker_id/lease_token are retained as an audit trail, mirroring
    # complete_agent_task.py's own _mark_stale().
    assert recovered.worker_id == "worker-1"
    assert recovered.lease_token is not None


def test_scan_and_recover_handles_a_mixed_batch(wiring) -> None:
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=True)
    _seed_claimed_task(agent_task_repository, clock, attempt=2, max_attempts=2, lease_expired=True, state=AgentTaskState.RUNNING)
    _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=False)

    report = service.scan_and_recover(batch_size=50)

    assert report.scanned == 2
    assert report.retried == 1
    assert report.staled == 1


def test_scan_and_recover_respects_batch_size(wiring) -> None:
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    for _ in range(3):
        _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=True)

    report = service.scan_and_recover(batch_size=2)

    assert report.scanned == 2


def test_retry_task_retries_a_claimed_task_even_with_an_unexpired_lease(wiring) -> None:
    """SPEC-ARO-031 05-api-contracts §"Admin API": "retry failed task" — an explicit
    admin override of the *wait* for lease expiry, not of the CLAIMED/RUNNING
    precondition.
    """
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    agent_task_id = _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=False)

    view = service.retry_task(RetryAgentTaskCommand(agent_task_id))

    assert view.state is AgentTaskState.READY
    recovered = agent_task_repository.find_by_id(agent_task_id)
    assert recovered.attempt == 2
    assert recovered.lease_expires_at is None


def test_retry_task_marks_stale_when_the_attempt_budget_is_already_exhausted(wiring) -> None:
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    agent_task_id = _seed_claimed_task(agent_task_repository, clock, attempt=3, max_attempts=3, lease_expired=False)

    view = service.retry_task(RetryAgentTaskCommand(agent_task_id))

    assert view.state is AgentTaskState.STALE
    recovered = agent_task_repository.find_by_id(agent_task_id)
    assert recovered.attempt == 3


def test_retry_task_rejects_an_unknown_agent_task_id(wiring) -> None:
    service = wiring[0]

    with pytest.raises(AgentTaskNotFoundException):
        service.retry_task(RetryAgentTaskCommand(AgentTaskId.new_id()))


def test_retry_task_rejects_a_task_that_is_not_claimed_or_running(wiring) -> None:
    """03-state-machine: FAILED_FINAL is "不可重试失败" — never reachable through this
    admin capability, confirmed with the user before this spec's scope was committed to.
    Unlike scan_and_recover()'s own tolerant skip, this propagates a real error.
    """
    service, agent_task_repository, workflow_instance_repository, clock = wiring
    agent_task_id = _seed_claimed_task(agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=False)
    task = agent_task_repository.find_by_id(agent_task_id)
    agent_task_repository.save(dataclasses.replace(task, state=AgentTaskState.FAILED_FINAL, task_version=2))

    with pytest.raises(InvalidAgentTaskTransitionException):
        service.retry_task(RetryAgentTaskCommand(agent_task_id))


def test_scan_and_recover_resolves_the_real_ticket_id_and_ticket_cycle_id(caplog: pytest.LogCaptureFixture) -> None:
    """SPEC-ARO-036 12-observability §"Audit Events" ("recovery decision") and §"日志"
    (ticketId/ticketCycleId on every log line) — proves RecoverExpiredLeaseTasksService's
    new WorkflowInstanceRepository lookup actually resolves the real ids, not just its own
    defensive None fallback for a task whose workflow cannot be found. Builds its own
    AuditRecorder/InMemoryAuditRecordRepository directly (rather than the generic
    build_telemetry_collaborators() helper other tests in this file use) so the audit
    entry it produces can be inspected, mirroring tests/application/test_audit.py's own
    construction style.
    """
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    audit_record_repository = InMemoryAuditRecordRepository()
    clock = FakeClock()
    telemetry, _ = build_telemetry_collaborators(clock)
    audit_recorder = AuditRecorder(audit_record_repository, clock)
    service = RecoverExpiredLeaseTasksService(agent_task_repository, workflow_instance_repository, clock, telemetry, audit_recorder)

    workflow = _seed_workflow(workflow_instance_repository, clock)
    agent_task_id = _seed_claimed_task(
        agent_task_repository, clock, attempt=1, max_attempts=3, lease_expired=True, workflow_instance_id=workflow.id,
    )

    with caplog.at_level(logging.INFO):
        service.scan_and_recover(batch_size=50)

    [entry] = [e for e in audit_record_repository.find_all(limit=10) if e.resource_id == str(agent_task_id)]
    assert entry.audit_type == "RECOVERY_DECISION"
    assert entry.workflow_instance_id == workflow.id
    assert entry.ticket_id == workflow.ticket_id

    [message] = [r.getMessage() for r in caplog.records if "action=lease_recovery_retry" in r.getMessage()]
    assert f"ticket_id={workflow.ticket_id}" in message
    assert f"ticket_cycle_id={workflow.ticket_cycle_id}" in message
