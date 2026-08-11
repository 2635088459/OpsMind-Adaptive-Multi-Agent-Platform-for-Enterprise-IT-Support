"""13-package-and-class-design §"Application Layer": StartWorkflowService. Owns the
transaction boundary, the active-instance uniqueness check (02-business-invariants
§"Workflow Instance Invariants"), translating the caller-supplied
WorkflowDefinitionInput into the domain WorkflowDefinition (13-package-and-class-design
§"Class Boundaries": "Planner creates task graph but does not call tools"), the domain
call, the STARTED checkpoint (SPEC-ARO-005 04-use-cases UC-01 steps 4-6: create instance
-> write STARTED checkpoint -> Planner creates task graph), and the outbox write.
09-concurrency-and-idempotency §"Command Idempotency": "Start ... must include
idempotencyKey" — wrapped by CommandIdempotencyGuard, like every other idempotent
command. Shared by both the direct REST /workflows command and
ConsumeTicketCreatedService (SPEC-ARO-005) — a ticket.created-triggered start is not a
different code path, only a different caller that resolves its own
WorkflowDefinitionInput first.
"""

from __future__ import annotations

import json
import uuid

from agentruntime.application.commands import StartWorkflowCommand, TaskNodeInput, WorkflowDefinitionInput
from agentruntime.application.exceptions import DuplicateActiveWorkflowInstanceException
from agentruntime.application.ports_out import CheckpointRepository, ClockPort, CommandIdempotencyRepository, OutboxRepository, WorkflowInstanceRepository
from agentruntime.application.records import CheckpointRecord, OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services import task_graph_codec
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import checkpoint, workflow_instance
from agentruntime.domain.enums import CheckpointType, JoinPolicy
from agentruntime.domain.events import WorkflowStarted
from agentruntime.domain.ids import CausationId, CheckpointId, CorrelationId, DefinitionVersion, WorkflowDefinitionId, WorkflowInstanceId, WorkflowType
from agentruntime.domain.task_graph import TaskGraph, TaskNode, WorkflowDefinition

_EVENT_TYPE = "agent_runtime.workflow.started"
_EVENT_SCHEMA_VERSION = 1
_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "start_workflow"


class StartWorkflowService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        checkpoint_repository: CheckpointRepository,
        outbox_repository: OutboxRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        coordinate_agent_tasks_service: CoordinateAgentTasksService,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._coordinate_agent_tasks_service = coordinate_agent_tasks_service
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def start(self, command: StartWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {
            "ticketId": str(command.ticket_id), "ticketCycleId": str(command.ticket_cycle_id),
            "definitionId": command.definition.definition_id, "definitionVersion": command.definition.definition_version,
            "workflowType": command.definition.workflow_type,
            "taskGraph": [
                {
                    "taskKey": n.task_key, "taskType": n.task_type, "dependsOn": sorted(n.depends_on),
                    "joinPolicy": n.join_policy, "agentRole": n.agent_role,
                }
                for n in command.definition.task_graph
            ],
        }
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.ticket_id), command.idempotency_key, request_payload,
            execute=lambda: self._start(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _start(self, command: StartWorkflowCommand) -> WorkflowInstanceView:
        definition = self._to_domain_definition(command.definition)

        active_instance = self._workflow_instance_repository.find_active_by_ticket_cycle_and_type(
            command.ticket_id, command.ticket_cycle_id, definition.workflow_type
        )
        if active_instance is not None:
            raise DuplicateActiveWorkflowInstanceException()

        now = self._clock.now()
        workflow_instance_id = WorkflowInstanceId.new_id()
        event = workflow_instance.start(
            workflow_instance_id, command.ticket_id, command.ticket_cycle_id, definition.workflow_type,
            definition.id, definition.version, now,
        )

        record = WorkflowInstanceRecord(
            id=workflow_instance_id, ticket_id=command.ticket_id, ticket_cycle_id=command.ticket_cycle_id,
            workflow_type=definition.workflow_type, definition_id=definition.id, definition_version=definition.version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=0, created_at=now, updated_at=now,
        )
        saved = self._workflow_instance_repository.save(record)

        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), workflow_instance_id=workflow_instance_id, ticket_id=command.ticket_id,
            correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), event_type=_EVENT_TYPE,
            schema_version=_EVENT_SCHEMA_VERSION, payload=self._to_payload(event), occurred_at=now,
        ))

        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), workflow_instance_id, CheckpointType.STARTED,
            _CHECKPOINT_SCHEMA_VERSION, task_graph_codec.encode(definition), now,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
        ))

        self._coordinate_agent_tasks_service.materialize_runnable_tasks(workflow_instance_id, event.to_state, definition.task_graph, now)

        return WorkflowInstanceView.from_record(saved)

    def _to_domain_definition(self, definition_input: WorkflowDefinitionInput) -> WorkflowDefinition:
        nodes = tuple(self._to_domain_node(node_input) for node_input in definition_input.task_graph)
        return WorkflowDefinition(
            id=WorkflowDefinitionId(definition_input.definition_id),
            version=DefinitionVersion(definition_input.definition_version),
            workflow_type=WorkflowType(definition_input.workflow_type),
            task_graph=TaskGraph(nodes),
        )

    def _to_domain_node(self, node_input: TaskNodeInput) -> TaskNode:
        try:
            join_policy = JoinPolicy[node_input.join_policy]
        except KeyError as exc:
            raise ValueError(f"unknown join_policy: {node_input.join_policy}") from exc
        return TaskNode(node_input.task_key, node_input.task_type, node_input.depends_on, join_policy, node_input.agent_role)

    def _to_payload(self, event: WorkflowStarted) -> str:
        return json.dumps({
            "workflowInstanceId": str(event.workflow_instance_id),
            "ticketId": str(event.ticket_id),
            "ticketCycleId": str(event.ticket_cycle_id),
            "workflowType": str(event.workflow_type),
            "definitionId": str(event.definition_id),
            "definitionVersion": event.definition_version.value,
            "toState": event.to_state.name,
            "workflowVersion": event.workflow_version,
            "occurredAt": event.occurred_at.isoformat(),
        })
