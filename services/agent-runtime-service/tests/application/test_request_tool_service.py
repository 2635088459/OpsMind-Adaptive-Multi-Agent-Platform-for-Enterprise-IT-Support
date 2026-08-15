from __future__ import annotations

import dataclasses
import uuid

import pytest

from agentruntime.application.commands import RequestToolCommand
from agentruntime.application.exceptions import (
    AgentTaskNotFoundException,
    CapabilityNotAuthorizedException,
    ClaimTokenMismatchException,
    IdempotencyKeyReusedException,
    WorkflowInstanceNotFoundException,
)
from agentruntime.application.records import AgentTaskRecord, WorkflowInstanceRecord
from agentruntime.application.services.request_tool import RequestToolService
from agentruntime.domain.enums import AgentTaskState, ToolRequestStatus, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    DefinitionVersion,
    IdempotencyKey,
    LeaseToken,
    TicketCycleId,
    TicketId,
    WorkflowDefinitionId,
    WorkflowInstanceId,
    WorkflowType,
)
from agentruntime.infrastructure.capability_policy import StaticCapabilityPolicyAdapter
from agentruntime.infrastructure.persistence.in_memory import (
    InMemoryAgentTaskRepository,
    InMemoryCheckpointRepository,
    InMemoryCommandIdempotencyRepository,
    InMemoryToolRequestRepository,
    InMemoryWorkflowInstanceRepository,
)
from tests.support.clock import FakeClock
from tests.support.telemetry import build_telemetry_collaborators

pytestmark = pytest.mark.unit


@pytest.fixture
def wiring():
    checkpoint_repository = InMemoryCheckpointRepository()
    tool_request_repository = InMemoryToolRequestRepository()
    command_idempotency_repository = InMemoryCommandIdempotencyRepository()
    workflow_instance_repository = InMemoryWorkflowInstanceRepository()
    agent_task_repository = InMemoryAgentTaskRepository()
    clock = FakeClock()
    _telemetry, audit_recorder = build_telemetry_collaborators(clock)
    service = RequestToolService(
        checkpoint_repository, tool_request_repository, command_idempotency_repository, clock,
        workflow_instance_repository, agent_task_repository, StaticCapabilityPolicyAdapter(), audit_recorder,
    )
    return service, checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock


def _start_workflow_instance(workflow_instance_repository, clock, workflow_version: int = 1) -> WorkflowInstanceId:
    """SPEC-ARO-011: RequestToolService now looks up the owning Workflow Instance to stamp
    the PRE_TOOL_CALL checkpoint's own workflow_version field, so every test needs a real
    saved record behind whatever workflow_instance_id it exercises. The repository's own
    optimistic-version CAS only accepts an insert at version 1, so reaching a higher
    workflow_version means legitimately re-saving one version at a time, not constructing
    a record at that version directly.
    """
    workflow_instance_id = WorkflowInstanceId.new_id()
    now = clock.now()
    record = WorkflowInstanceRecord(current_checkpoint_id=None, completed_at=None, 
        id=workflow_instance_id, ticket_id=TicketId(uuid.uuid4()), ticket_cycle_id=TicketCycleId(uuid.uuid4()),
        workflow_type=WorkflowType("TICKET_TRIAGE"), definition_id=WorkflowDefinitionId("triage-v1"),
        definition_version=DefinitionVersion(1), state=WorkflowState.RUNNING, workflow_version=1,
        pause_generation=0, created_at=now, updated_at=now,
    )
    workflow_instance_repository.save(record)
    for next_version in range(2, workflow_version + 1):
        record = dataclasses.replace(record, workflow_version=next_version, updated_at=now)
        workflow_instance_repository.save(record)
    return workflow_instance_id


def _claim_agent_task(
    agent_task_repository, workflow_instance_id: WorkflowInstanceId, clock, agent_role: str | None = None,
) -> tuple[AgentTaskId, LeaseToken]:
    """SPEC-ARO-018: RequestToolService validates claimToken against a real claimed Agent
    Task, so every test needs one behind whatever agent_task_id it exercises — a bare
    AgentTaskId.new_id() with no record at all is not enough.
    """
    agent_task_id = AgentTaskId.new_id()
    claim_token = LeaseToken.new_token()
    now = clock.now()
    agent_task_repository.save(AgentTaskRecord(
        id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.CLAIMED, task_version=1, worker_id="worker-1",
        lease_token=claim_token, lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now, agent_role=agent_role,
    ))
    return agent_task_id, claim_token


def _command(
    workflow_instance_id: WorkflowInstanceId, agent_task_id: AgentTaskId, claim_token: LeaseToken, idempotency_key: str = "tool-1",
    capability: str | None = None,
) -> RequestToolCommand:
    return RequestToolCommand(
        workflow_instance_id, agent_task_id, '{"before":"restart"}', "restart_service", '{"service":"api"}', IdempotencyKey(idempotency_key),
        claim_token, capability,
    )


def test_dispatching_a_tool_request_persists_a_checkpoint_first(wiring) -> None:
    """02-business-invariants §"Checkpoint Invariants" / §"Tool Gateway Boundary": a
    checkpoint must be persisted before the Tool Request even reaches PENDING.
    """
    service, checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token))

    checkpoints = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert len(checkpoints) == 1

    saved = tool_request_repository.find_by_id(view.tool_request_id)
    assert saved.preceding_checkpoint_id == checkpoints[0].id


def test_a_new_tool_request_is_persisted_pending_not_dispatched(wiring) -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 6: "Tool
    Gateway 调用不能在事务内直接同步执行" — RequestToolService only ever leaves a PENDING
    record; DispatchToolRequestsService is the one that later moves it to DISPATCHED.
    """
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token))

    assert view.status is ToolRequestStatus.PENDING
    assert tool_request_repository.find_pending(10)[0].id == view.tool_request_id


def test_requesting_a_tool_moves_the_agent_task_to_waiting_tool(wiring) -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 4: "Set
    task to WAITING_TOOL."
    """
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token))

    task = agent_task_repository.find_by_id(agent_task_id)
    assert task.state is AgentTaskState.WAITING_TOOL
    assert task.task_version == 2


def test_requesting_a_tool_moves_the_workflow_instance_to_waiting_for_tool(wiring) -> None:
    """SPEC-ARO-019 08-transaction-and-outbox §"Tool Request Transaction" step 5: "Set
    workflow to WAITING_FOR_TOOL."
    """
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token))

    workflow = workflow_instance_repository.find_by_id(workflow_instance_id)
    assert workflow.state is WorkflowState.WAITING_FOR_TOOL
    assert workflow.workflow_version == 2


def test_the_checkpoint_carries_the_owning_workflow_instances_current_version(wiring) -> None:
    """SPEC-ARO-011 01-domain-model: workflow_version is one of Checkpoint's own minimal
    fields.
    """
    service, checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock, workflow_version=5)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token))

    [recorded] = checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)
    assert recorded.workflow_version == 5
    assert recorded.checksum


def test_the_tool_request_carries_the_commands_idempotency_key(wiring) -> None:
    """SPEC-ARO-017 01-domain-model/07-data-model: idempotency_key is one of Tool
    Request's own minimal fields — populated from the same value
    CommandIdempotencyGuard already keys the whole command's replay cache on, not a
    second independent one.
    """
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, "tool-idempotency-1"))

    assert tool_request_repository.find_by_id(view.tool_request_id).idempotency_key == "tool-idempotency-1"


def test_capability_is_threaded_onto_the_tool_request_when_the_caller_supplies_one(wiring) -> None:
    """SPEC-ARO-017 01-domain-model: capability is one of Tool Request's own minimal
    fields, alongside toolName.
    """
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, capability="service_operations"))

    assert tool_request_repository.find_by_id(view.tool_request_id).capability == "service_operations"


def test_capability_defaults_to_none_when_the_caller_does_not_supply_one(wiring) -> None:
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token))

    assert tool_request_repository.find_by_id(view.tool_request_id).capability is None


def test_the_returned_view_echoes_capability_back_to_the_caller(wiring) -> None:
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, capability="service_operations"))

    assert view.capability == "service_operations"


def test_capability_survives_the_idempotency_guards_cached_replay(wiring) -> None:
    """09-concurrency-and-idempotency §"Command Idempotency": the cached replay path
    round-trips the view through ToolRequestView.to_dict()/from_dict() — capability must
    survive that round trip too.
    """
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)
    command = _command(workflow_instance_id, agent_task_id, claim_token, "tool-replay-1", capability="service_operations")
    first = service.request_tool(command)

    second = service.request_tool(command)

    assert second.tool_request_id == first.tool_request_id
    assert second.capability == "service_operations"


def test_requesting_a_tool_for_an_unknown_workflow_instance_is_rejected(wiring) -> None:
    service = wiring[0]

    with pytest.raises(WorkflowInstanceNotFoundException):
        service.request_tool(_command(WorkflowInstanceId.new_id(), AgentTaskId.new_id(), LeaseToken.new_token()))


def test_requesting_a_tool_for_an_unknown_agent_task_is_rejected(wiring) -> None:
    """SPEC-ARO-018 02-business-invariants §"Tool Gateway Boundary" / 11-security
    §"Authorization": a workflow_instance_id alone is not enough — there must be a real
    Agent Task behind the request.
    """
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, _agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)

    with pytest.raises(AgentTaskNotFoundException):
        service.request_tool(_command(workflow_instance_id, AgentTaskId.new_id(), LeaseToken.new_token()))


def test_requesting_a_tool_with_the_wrong_claim_token_is_rejected(wiring) -> None:
    """SPEC-ARO-018: closes the gap where knowing a workflow_instance_id and
    agent_task_id alone (neither secret) was enough to dispatch a tool request — proof
    of holding the task's own claim is now required, the same as claim/complete.
    """
    service, checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, _real_claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)

    with pytest.raises(ClaimTokenMismatchException):
        service.request_tool(_command(workflow_instance_id, agent_task_id, LeaseToken.new_token()))

    assert checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id) == []
    assert tool_request_repository.find_pending(10) == []
    assert agent_task_repository.find_by_id(agent_task_id).state is AgentTaskState.CLAIMED


def test_requesting_a_tool_for_a_never_claimed_task_is_rejected(wiring) -> None:
    """A task that was materialized but never claimed has no lease_token at all — must
    be rejected the same way a wrong claim_token is, not treated as a free pass.
    """
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id = AgentTaskId.new_id()
    now = clock.now()
    agent_task_repository.save(AgentTaskRecord(
        id=agent_task_id, workflow_instance_id=workflow_instance_id, task_key="collect", task_type="collect_diagnostics",
        depends_on_task_keys=frozenset(), state=AgentTaskState.READY, task_version=1, worker_id=None,
        lease_token=None, lease_expires_at=None, result_payload=None, failure_reason=None, pause_generation=0,
        created_at=now, updated_at=now,
    ))

    with pytest.raises(ClaimTokenMismatchException):
        service.request_tool(_command(workflow_instance_id, agent_task_id, LeaseToken.new_token()))


def test_duplicate_request_with_the_same_key_does_not_create_a_second_tool_request(wiring) -> None:
    """09-concurrency-and-idempotency §"Command Idempotency": without this, a retried
    request would write a second Checkpoint and a second Tool Request.
    """
    service, checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)
    command = _command(workflow_instance_id, agent_task_id, claim_token, "tool-1")

    first = service.request_tool(command)
    second = service.request_tool(command)

    assert second.tool_request_id == first.tool_request_id
    assert len(tool_request_repository.find_pending(10)) == 1
    assert len(checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id)) == 1


def test_request_with_a_reused_key_but_different_request_is_rejected(wiring) -> None:
    """The idempotency guard's hash check runs before this service ever looks at
    AgentTaskRepository, so a mismatched claim_token here would never surface — a
    genuinely different agentTaskId under the reused key is what must trip
    IdempotencyKeyReusedException.
    """
    service, _checkpoint_repository, _tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    first_agent_task_id, first_claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)
    second_agent_task_id, second_claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock)
    service.request_tool(_command(workflow_instance_id, first_agent_task_id, first_claim_token, "tool-1"))

    with pytest.raises(IdempotencyKeyReusedException):
        service.request_tool(_command(workflow_instance_id, second_agent_task_id, second_claim_token, "tool-1"))


def test_a_capability_authorized_for_the_claiming_agent_role_succeeds(wiring) -> None:
    """SPEC-ARO-032 11-security §"Tool Gateway 强制路径": StaticCapabilityPolicyAdapter's
    own default policy authorizes triage_agent for service_operations.
    """
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock, agent_role="triage_agent")

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, capability="service_operations"))

    assert view.capability == "service_operations"
    assert tool_request_repository.find_by_id(view.tool_request_id) is not None


def test_a_capability_not_authorized_for_the_claiming_agent_role_is_rejected(wiring) -> None:
    service, checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock, agent_role="kb_agent")

    with pytest.raises(CapabilityNotAuthorizedException):
        service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, capability="service_operations"))

    # Rejected before any persistence — no checkpoint, no Tool Request.
    assert checkpoint_repository.find_by_workflow_instance_id(workflow_instance_id) == []
    assert tool_request_repository.find_pending(10) == []


def test_a_declared_capability_with_no_assigned_agent_role_is_a_pass_through(wiring) -> None:
    """SPEC-ARO-007's own "no Planner capability assigns agent_role yet" deferral is a
    separate, still-pending gap this spec does not close — an absent agent_role must not
    be treated as an implicit denial.
    """
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock, agent_role=None)

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, capability="service_operations"))

    assert tool_request_repository.find_by_id(view.tool_request_id) is not None


def test_no_declared_capability_skips_authorization_entirely(wiring) -> None:
    service, _checkpoint_repository, tool_request_repository, workflow_instance_repository, agent_task_repository, clock = wiring
    workflow_instance_id = _start_workflow_instance(workflow_instance_repository, clock)
    agent_task_id, claim_token = _claim_agent_task(agent_task_repository, workflow_instance_id, clock, agent_role="kb_agent")

    view = service.request_tool(_command(workflow_instance_id, agent_task_id, claim_token, capability=None))

    assert tool_request_repository.find_by_id(view.tool_request_id) is not None
