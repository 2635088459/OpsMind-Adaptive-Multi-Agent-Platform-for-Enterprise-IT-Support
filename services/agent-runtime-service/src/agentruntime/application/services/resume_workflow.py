"""13-package-and-class-design §"Application Layer": ResumeWorkflowService.
09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "If workflow already
resumed, use idempotency key to decide saved response or conflict" — unlike Pause,
Resume gets no extra leniency: CommandIdempotencyGuard already returns the cached
response for a repeated key (handled before _resume() is ever called); a *new* key
against an already-RUNNING workflow reaches domain.workflow_instance.resume(), which
requires current_state == PAUSED and raises otherwise — that natural domain guard *is*
the "conflict" outcome the LLD describes.

SPEC-ARO-014/015 04-use-cases UC-07 Resume step 4: "Read the PAUSED checkpoint" — once
the domain call confirms the workflow genuinely was PAUSED, this service also confirms
the PAUSE_POINT checkpoint SPEC-ARO-012's Pause Transaction wrote is actually there
before letting the transition proceed (PauseCheckpointNotFoundException otherwise). UC-07
step 5 ("restore incomplete, non-cancelled tasks to READY") needs no code here: an Agent
Task's own state is never mutated by pause/resume — READY tasks stay READY and simply
become claimable again the instant this service flips the workflow back to RUNNING
(ClaimAgentTaskService's own RUNNING gate), and a CLAIMED/RUNNING task's staleness is
already caught by StalePauseGenerationException on completion, not by anything Resume
itself needs to do.
"""

from __future__ import annotations

import json
import logging
import uuid

from opentelemetry import trace

from agentruntime.application.commands import ResumeWorkflowCommand
from agentruntime.application.exceptions import PauseCheckpointNotFoundException, WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import CheckpointRepository, ClockPort, CommandIdempotencyRepository, OutboxRepository, WorkflowInstanceRepository
from agentruntime.application.records import OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import workflow_instance
from agentruntime.domain.enums import CheckpointType
from agentruntime.domain.events import WorkflowResumed
from agentruntime.domain.ids import CausationId, CorrelationId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# SPEC-ARO-025 06-event-contracts §"workflow.resumed.v1": see start_workflow.py's own
# _EVENT_TYPE comment for why this is a rename, not an alias.
_EVENT_TYPE = "workflow.resumed.v1"
_EVENT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "resume_workflow"


class ResumeWorkflowService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        outbox_repository: OutboxRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        checkpoint_repository: CheckpointRepository,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._checkpoint_repository = checkpoint_repository
        self._audit_recorder = audit_recorder
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def resume(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {"workflowInstanceId": str(command.workflow_instance_id)}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.workflow_instance_id), command.idempotency_key, request_payload,
            execute=lambda: self._resume(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _resume(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView:
        with tracer.start_as_current_span("workflow.resume"):
            return self._resume_traced(command)

    def _resume_traced(self, command: ResumeWorkflowCommand) -> WorkflowInstanceView:
        current = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if current is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        now = self._clock.now()
        event = workflow_instance.resume(
            command.workflow_instance_id, current.state, current.workflow_version, current.pause_generation,
            command.idempotency_key, now,
        )

        pause_checkpoint = self._checkpoint_repository.find_latest_by_workflow_instance_id_and_type(
            command.workflow_instance_id, CheckpointType.PAUSE_POINT
        )
        if pause_checkpoint is None:
            raise PauseCheckpointNotFoundException(command.workflow_instance_id)

        # SPEC-ARO-028: resume writes no new checkpoint of its own, so
        # current_checkpoint_id/completed_at simply carry forward unchanged.
        updated_record = WorkflowInstanceRecord(
            id=current.id, ticket_id=current.ticket_id, ticket_cycle_id=current.ticket_cycle_id,
            workflow_type=current.workflow_type, definition_id=current.definition_id, definition_version=current.definition_version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=event.pause_generation,
            current_checkpoint_id=current.current_checkpoint_id, completed_at=current.completed_at,
            created_at=current.created_at, updated_at=now,
        )
        saved = self._workflow_instance_repository.save(updated_record)

        correlation_id = CorrelationId.new_id()
        causation_id = CausationId.new_id()
        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=correlation_id, causation_id=causation_id, event_type=_EVENT_TYPE,
            schema_version=_EVENT_SCHEMA_VERSION, payload=self._to_payload(event), occurred_at=now,
        ))

        logger.info(
            "action=resume_workflow status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "correlation_id=%s causation_id=%s",
            current.id, current.ticket_id, current.ticket_cycle_id, correlation_id, causation_id,
        )
        self._audit_recorder.record(
            "WORKFLOW_TRANSITION", "resume_workflow", "WorkflowInstance", str(current.id), "SUCCESS",
            workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=str(correlation_id), causation_id=str(causation_id),
        )
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
