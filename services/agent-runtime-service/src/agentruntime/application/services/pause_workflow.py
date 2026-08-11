"""13-package-and-class-design §"Application Layer": PauseWorkflowService.
09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "First successful
pause writes command_idempotency. Duplicate pause returns saved response directly. If
workflow is already PAUSED without the same idempotency key, return current paused
state and do not publish another event." The "same idempotency key" case is handled by
CommandIdempotencyGuard before _pause() is ever called (a cache hit never reaches this
class); reaching _pause() with an already-PAUSED workflow therefore always means the
second, more lenient case — no error, no new event, just the current state.
"""

from __future__ import annotations

import json
import uuid

from agentruntime.application.commands import PauseWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import ClockPort, CommandIdempotencyRepository, OutboxRepository, WorkflowInstanceRepository
from agentruntime.application.records import OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import workflow_instance
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.events import WorkflowPaused
from agentruntime.domain.ids import CausationId, CorrelationId

_EVENT_TYPE = "agent_runtime.workflow.paused"
_EVENT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "pause_workflow"


class PauseWorkflowService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        outbox_repository: OutboxRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def pause(self, command: PauseWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {"workflowInstanceId": str(command.workflow_instance_id)}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.workflow_instance_id), command.idempotency_key, request_payload,
            execute=lambda: self._pause(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _pause(self, command: PauseWorkflowCommand) -> WorkflowInstanceView:
        current = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if current is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        if current.state is WorkflowState.PAUSED:
            return WorkflowInstanceView.from_record(current)

        now = self._clock.now()
        event = workflow_instance.pause(
            command.workflow_instance_id, current.state, current.workflow_version, current.pause_generation,
            command.idempotency_key, now,
        )

        updated_record = WorkflowInstanceRecord(
            id=current.id, ticket_id=current.ticket_id, ticket_cycle_id=current.ticket_cycle_id,
            workflow_type=current.workflow_type, definition_id=current.definition_id, definition_version=current.definition_version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=event.pause_generation,
            created_at=current.created_at, updated_at=now,
        )
        saved = self._workflow_instance_repository.save(updated_record)

        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=CorrelationId.new_id(), causation_id=CausationId.new_id(), event_type=_EVENT_TYPE,
            schema_version=_EVENT_SCHEMA_VERSION, payload=self._to_payload(event), occurred_at=now,
        ))

        return WorkflowInstanceView.from_record(saved)

    def _to_payload(self, event: WorkflowPaused) -> str:
        return json.dumps({
            "workflowInstanceId": str(event.workflow_instance_id),
            "fromState": event.from_state.name if event.from_state else None,
            "toState": event.to_state.name,
            "workflowVersion": event.workflow_version,
            "pauseGeneration": event.pause_generation,
            "idempotencyKey": str(event.idempotency_key),
            "occurredAt": event.occurred_at.isoformat(),
        })
