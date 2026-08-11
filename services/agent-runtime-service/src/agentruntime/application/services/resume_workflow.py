"""13-package-and-class-design §"Application Layer": ResumeWorkflowService.
09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "If workflow already
resumed, use idempotency key to decide saved response or conflict" — unlike Pause,
Resume gets no extra leniency: CommandIdempotencyGuard already returns the cached
response for a repeated key (handled before _resume() is ever called); a *new* key
against an already-RUNNING workflow reaches domain.workflow_instance.resume(), which
requires current_state == PAUSED and raises otherwise — that natural domain guard *is*
the "conflict" outcome the LLD describes.
"""

from __future__ import annotations

import json
import uuid

from agentruntime.application.commands import ResumeWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import ClockPort, CommandIdempotencyRepository, OutboxRepository, WorkflowInstanceRepository
from agentruntime.application.records import OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import workflow_instance
from agentruntime.domain.events import WorkflowResumed
from agentruntime.domain.ids import CausationId, CorrelationId

_EVENT_TYPE = "agent_runtime.workflow.resumed"
_EVENT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "resume_workflow"


class ResumeWorkflowService:
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

    def resume(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {"workflowInstanceId": str(command.workflow_instance_id)}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.workflow_instance_id), command.idempotency_key, request_payload,
            execute=lambda: self._resume(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _resume(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView:
        current = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if current is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        now = self._clock.now()
        event = workflow_instance.resume(
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

    def _to_payload(self, event: WorkflowResumed) -> str:
        return json.dumps({
            "workflowInstanceId": str(event.workflow_instance_id),
            "fromState": event.from_state.name if event.from_state else None,
            "toState": event.to_state.name,
            "workflowVersion": event.workflow_version,
            "pauseGeneration": event.pause_generation,
            "idempotencyKey": str(event.idempotency_key),
            "occurredAt": event.occurred_at.isoformat(),
        })
