from __future__ import annotations

import dataclasses
import uuid
from datetime import timedelta

import pytest

from agentruntime.application.commands import ClaimAgentTaskCommand, ClaimReadyAgentTasksCommand
from agentruntime.application.exceptions import AgentTaskNotFoundException, WorkflowNotRunningException
from agentruntime.application.records import AgentTaskRecord, WorkflowInstanceRecord
from agentruntime.application.services.claim_agent_task import ClaimAgentTaskService
from agentruntime.domain.enums import AgentTaskState, WorkflowState
from agentruntime.domain.exceptions import AgentTaskAlreadyClaimedException, AgentTaskDependencyNotSatisfiedException
from agentruntime.domain.ids import AgentTaskId, DefinitionVersion, TicketCycleId, TicketId, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.infrastructure.persistence.in_memory import InMemoryAgentTaskRepository, InMemoryWorkflowInstanceRepository
from tests.support.clock import FakeClock

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    clock = FakeClock()
    service = ClaimAgentTaskService(agent_task_repository, workflow_instance_repository, clock)
    workflow_instance_id = WorkflowInstanceId.new_id()

    now = clock.now()
    workflow_instance_repository.save(WorkflowInstanceRecord(
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    agent_task_repository.save(AgentTaskRecord(
        id=AgentTaskId.new_id(), workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.READY, task_version=1, worker_id=None, lease_token=None,
        lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0, created_at=now, updated_at=now,
    ))
    agent_task_repository.save(AgentTaskRecord(
        id=AgentTaskId.new_id(), workflow_instance_id=workflow_instance_id, task_key="remediate", task_type="apply_fix",
        depends_on_task_keys=frozenset({"collect"}), state=AgentTaskState.PENDING, task_version=1, worker_id=None,
        lease_token=None, lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    ))
    return service, workflow_instance_repository, workflow_instance_id, clock


def test_claiming_a_task_whose_dependency_has_not_completed_is_rejected(wiring) -> None:
    service, _, workflow_instance_id, _ = wiring

    with pytest.raises(AgentTaskDependencyNotSatisfiedException):
        service.claim(ClaimAgentTaskCommand(workflow_instance_id, "remediate", "worker-1", 300))


def test_claiming_a_root_task_succeeds(wiring) -> None:
    service, _, workflow_instance_id, _ = wiring

    view = service.claim(ClaimAgentTaskCommand(workflow_instance_id, "collect", "worker-1", 300))

    assert view.state is AgentTaskState.CLAIMED
    assert view.task_version == 2
    assert view.claim_token is not None
    # SPEC-ARO-009 09-concurrency-and-idempotency §"Workflow Version": returned so the
    # worker has something to resubmit at completion.
    assert view.workflow_version == 1


def test_claiming_an_unknown_task_key_is_rejected(wiring) -> None:
    service, _, workflow_instance_id, _ = wiring

    with pytest.raises(AgentTaskNotFoundException):
        service.claim(ClaimAgentTaskCommand(workflow_instance_id, "unknown", "worker-1", 300))


def test_reclaiming_under_an_unexpired_lease_is_rejected(wiring) -> None:
    service, _, workflow_instance_id, _ = wiring
    service.claim(ClaimAgentTaskCommand(workflow_instance_id, "collect", "worker-1", 300))

    with pytest.raises(AgentTaskAlreadyClaimedException):
        service.claim(ClaimAgentTaskCommand(workflow_instance_id, "collect", "worker-2", 300))


def test_reclaiming_after_lease_expiry_is_allowed(wiring) -> None:
    service, _, workflow_instance_id, clock = wiring
    service.claim(ClaimAgentTaskCommand(workflow_instance_id, "collect", "worker-1", 30))
    clock.advance(timedelta(minutes=1))

    view = service.claim(ClaimAgentTaskCommand(workflow_instance_id, "collect", "worker-2", 300))

    assert view.state is AgentTaskState.CLAIMED


def test_claiming_under_a_paused_workflow_is_rejected(wiring) -> None:
    """09-concurrency-and-idempotency §"Task Claim": "Workflow must be in RUNNING."."""
    service, workflow_instance_repository, workflow_instance_id, clock = wiring
    current = workflow_instance_repository.find_by_id(workflow_instance_id)

    workflow_instance_repository.save(dataclasses.replace(
        current, state=WorkflowState.PAUSED, workflow_version=2, pause_generation=1, updated_at=clock.now()
    ))

    with pytest.raises(WorkflowNotRunningException):
        service.claim(ClaimAgentTaskCommand(workflow_instance_id, "collect", "worker-1", 300))


@pytest.fixture
def ready_pool():
    """SPEC-ARO-009: a separate fixture from `wiring` — claim_ready() polls by
    agent_role across potentially many Workflow Instances, a different shape of setup
    than the single-instance `wiring` fixture above.
    """
    agent_task_repository = InMemoryAgentTaskRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    clock = FakeClock()
    service = ClaimAgentTaskService(agent_task_repository, workflow_instance_repository, clock)
    now = clock.now()

    def add_workflow_instance(state: WorkflowState = WorkflowState.RUNNING) -> WorkflowInstanceId:
        workflow_instance_id = WorkflowInstanceId.new_id()
        workflow_instance_repository.save(WorkflowInstanceRecord(
            id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
            workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
            definition_version=DefinitionVersion(1), state=state, workflow_version=1, pause_generation=0,
            created_at=now, updated_at=now,
        ))
        return workflow_instance_id

    def add_ready_task(workflow_instance_id: WorkflowInstanceId, task_key: str, agent_role: str | None) -> AgentTaskId:
        agent_task_id = AgentTaskId.new_id()
        agent_task_repository.save(AgentTaskRecord(
            id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key=task_key, task_type="collect_diagnostics",
            depends_on_task_keys=frozenset(), state=AgentTaskState.READY, task_version=1, worker_id=None, lease_token=None,
            lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0, created_at=now, updated_at=now,
            agent_role=agent_role,
        ))
        return agent_task_id

    return service, workflow_instance_repository, agent_task_repository, add_workflow_instance, add_ready_task


def test_claim_ready_claims_up_to_max_tasks_matching_the_role(ready_pool) -> None:
    service, _, _, add_workflow_instance, add_ready_task = ready_pool
    workflow_instance_id = add_workflow_instance()
    add_ready_task(workflow_instance_id, "collect-a", "triage_agent")
    add_ready_task(workflow_instance_id, "collect-b", "triage_agent")
    add_ready_task(workflow_instance_id, "collect-c", "triage_agent")
    add_ready_task(workflow_instance_id, "other-role-task", "kb_agent")

    claimed = service.claim_ready(ClaimReadyAgentTasksCommand("triage_agent", "worker-1", 2, 300))

    assert len(claimed) == 2
    assert all(view.state is AgentTaskState.CLAIMED for view in claimed)
    assert all(view.workflow_version == 1 for view in claimed)


def test_claim_ready_returns_fewer_than_max_tasks_when_the_pool_is_smaller(ready_pool) -> None:
    service, _, _, add_workflow_instance, add_ready_task = ready_pool
    workflow_instance_id = add_workflow_instance()
    add_ready_task(workflow_instance_id, "collect-a", "triage_agent")

    claimed = service.claim_ready(ClaimReadyAgentTasksCommand("triage_agent", "worker-1", 5, 300))

    assert len(claimed) == 1


def test_claim_ready_finds_nothing_for_an_unmatched_role(ready_pool) -> None:
    service, _, _, add_workflow_instance, add_ready_task = ready_pool
    workflow_instance_id = add_workflow_instance()
    add_ready_task(workflow_instance_id, "collect-a", "triage_agent")

    claimed = service.claim_ready(ClaimReadyAgentTasksCommand("verification_agent", "worker-1", 5, 300))

    assert claimed == []


def test_claim_ready_skips_a_task_never_assigned_a_role(ready_pool) -> None:
    """A task with agent_role=None is only reachable through claim() by exact task_key,
    never through the role-based pool.
    """
    service, _, _, add_workflow_instance, add_ready_task = ready_pool
    workflow_instance_id = add_workflow_instance()
    add_ready_task(workflow_instance_id, "unassigned", None)

    claimed = service.claim_ready(ClaimReadyAgentTasksCommand("triage_agent", "worker-1", 5, 300))

    assert claimed == []


def test_claim_ready_skips_a_candidate_whose_workflow_is_not_running(ready_pool) -> None:
    """09-concurrency-and-idempotency §"Task Claim": "Workflow must be in RUNNING" —
    applies per-candidate here too, but a batch poll skips rather than fails outright.
    """
    service, _, _, add_workflow_instance, add_ready_task = ready_pool
    paused_workflow_instance_id = add_workflow_instance(state=WorkflowState.PAUSED)
    add_ready_task(paused_workflow_instance_id, "collect-a", "triage_agent")
    running_workflow_instance_id = add_workflow_instance()
    add_ready_task(running_workflow_instance_id, "collect-b", "triage_agent")

    claimed = service.claim_ready(ClaimReadyAgentTasksCommand("triage_agent", "worker-1", 5, 300))

    assert [view.task_key for view in claimed] == ["collect-b"]


def test_claim_ready_spans_multiple_workflow_instances(ready_pool) -> None:
    service, _, _, add_workflow_instance, add_ready_task = ready_pool
    first_workflow_instance_id = add_workflow_instance()
    add_ready_task(first_workflow_instance_id, "collect-a", "triage_agent")
    second_workflow_instance_id = add_workflow_instance()
    add_ready_task(second_workflow_instance_id, "collect-b", "triage_agent")

    claimed = service.claim_ready(ClaimReadyAgentTasksCommand("triage_agent", "worker-1", 5, 300))

    assert {view.task_key for view in claimed} == {"collect-a", "collect-b"}
