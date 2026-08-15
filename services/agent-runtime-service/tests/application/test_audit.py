from __future__ import annotations

import pytest

from agentruntime.application.services.audit import AuditRecorder
from agentruntime.domain.ids import WorkflowInstanceId
from agentruntime.infrastructure.persistence.in_memory import InMemoryAuditRecordRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


class _RaisingAuditRecordRepository:
    """Stands in for a real repository whose append() fails (e.g. a transient DB
    error) — AuditRecorder must swallow this, not propagate it (see AuditRecorder's
    own module docstring for why: an audit-append failure must not fail the primary
    operation it is describing).
    """

    def append(self, entry) -> None:  # noqa: ANN001, ANN201 - test double
        raise RuntimeError("simulated audit append failure")


def test_record_appends_an_entry_with_the_supplied_fields() -> None:
    repository = InMemoryAuditRecordRepository()
    clock = FakeClock()
    recorder = AuditRecorder(repository, clock)
    workflow_instance_id = WorkflowInstanceId.new_id()

    recorder.record(
        "WORKFLOW_TRANSITION", "start_workflow", "WorkflowInstance", str(workflow_instance_id), "SUCCESS",
        workflow_instance_id=workflow_instance_id, actor_type="SYSTEM", correlation_id="corr-1", causation_id="cause-1",
        detail='{"workflow_type": "TICKET_TRIAGE"}',
    )

    [entry] = repository.find_all(10)
    assert entry.audit_type == "WORKFLOW_TRANSITION"
    assert entry.action == "start_workflow"
    assert entry.resource_type == "WorkflowInstance"
    assert entry.resource_id == str(workflow_instance_id)
    assert entry.workflow_instance_id == workflow_instance_id
    assert entry.outcome == "SUCCESS"
    assert entry.actor_type == "SYSTEM"
    assert entry.correlation_id == "corr-1"
    assert entry.causation_id == "cause-1"
    assert entry.occurred_at == clock.now()


def test_record_defaults_actor_type_to_system_and_detail_to_an_empty_object() -> None:
    repository = InMemoryAuditRecordRepository()
    recorder = AuditRecorder(repository, FakeClock())

    recorder.record("TASK_TRANSITION", "claim_task", "AgentTask", "some-id", "SUCCESS")

    [entry] = repository.find_all(10)
    assert entry.actor_type == "SYSTEM"
    assert entry.actor_id is None
    assert entry.detail == "{}"
    assert entry.workflow_instance_id is None
    assert entry.ticket_id is None


def test_a_failing_repository_does_not_propagate() -> None:
    """The primary operation this call is describing must not fail just because the
    audit trail itself could not be written.
    """
    recorder = AuditRecorder(_RaisingAuditRecordRepository(), FakeClock())

    recorder.record("ADMIN_INTERVENTION", "force_recover_workflow", "WorkflowInstance", "some-id", "SUCCESS")
