"""13-package-and-class-design §"Application Layer": PauseWorkflowService.
09-concurrency-and-idempotency §"How Pause / Resume Is Idempotent": "First successful
pause writes command_idempotency. Duplicate pause returns saved response directly. If
workflow is already PAUSED without the same idempotency key, return current paused
state and do not publish another event." The "same idempotency key" case is handled by
CommandIdempotencyGuard before _pause() is ever called (a cache hit never reaches this
class); reaching _pause() with an already-PAUSED workflow therefore always means the
second, more lenient case — no error, no new event, just the current state.

SPEC-ARO-012 08-transaction-and-outbox §"Pause Transaction" step 6: "Write PAUSED
checkpoint" — PAUSED is exactly the "recoverable waiting state" 02-business-invariants
§"Checkpoint Invariants" means by "every recoverable waiting state must include a
checkpoint": no new READY task can be claimed while paused, and resuming has nothing
else to reconstruct from but this checkpoint plus the Workflow Instance row itself.
Written with CheckpointType.PAUSE_POINT — declared since SPEC-ARO-008 but never written
by any service until now.
"""

from __future__ import annotations

import json
import logging
import uuid

from opentelemetry import trace

from agentruntime.application.commands import PauseWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import (
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    OutboxRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import CheckpointRecord, OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import checkpoint, workflow_instance
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.events import WorkflowPaused
from agentruntime.domain.ids import CausationId, CheckpointId, CorrelationId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# SPEC-ARO-025 06-event-contracts §"workflow.paused.v1": see start_workflow.py's own
# _EVENT_TYPE comment for why this is a rename, not an alias.
_EVENT_TYPE = "workflow.paused.v1"
_EVENT_SCHEMA_VERSION = 1
_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "pause_workflow"


class PauseWorkflowService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        outbox_repository: OutboxRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        checkpoint_repository: CheckpointRepository,
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._outbox_repository = outbox_repository
        self._clock = clock
        self._checkpoint_repository = checkpoint_repository
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def pause(self, command: PauseWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {"workflowInstanceId": str(command.workflow_instance_id)}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.workflow_instance_id), command.idempotency_key, request_payload,
            execute=lambda: self._pause(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _pause(self, command: PauseWorkflowCommand) -> WorkflowInstanceView:
        with tracer.start_as_current_span("workflow.pause"):
            return self._pause_traced(command)

    def _pause_traced(self, command: PauseWorkflowCommand) -> WorkflowInstanceView:
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

        # SPEC-ARO-028: minted up front so the same save() below can point
        # current_checkpoint_id at it — no second write purely to record the pointer.
        checkpoint_id = CheckpointId.new_id()
        updated_record = WorkflowInstanceRecord(
            id=current.id, ticket_id=current.ticket_id, ticket_cycle_id=current.ticket_cycle_id,
            workflow_type=current.workflow_type, definition_id=current.definition_id, definition_version=current.definition_version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=event.pause_generation,
            current_checkpoint_id=checkpoint_id, completed_at=current.completed_at, created_at=current.created_at, updated_at=now,
            requester_subject=current.requester_subject, ticket_version=current.ticket_version,
            ticket_display_id=current.ticket_display_id,
        )
        saved = self._workflow_instance_repository.save(updated_record)

        correlation_id = CorrelationId.new_id()
        causation_id = CausationId.new_id()
        self._outbox_repository.append(OutboxRecord(
            outbox_id=uuid.uuid4(), workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=correlation_id, causation_id=causation_id, event_type=_EVENT_TYPE,
            schema_version=_EVENT_SCHEMA_VERSION, payload=self._to_payload(event), occurred_at=now,
        ))

        checkpoint_event = checkpoint.record(
            checkpoint_id, current.id, CheckpointType.PAUSE_POINT, _CHECKPOINT_SCHEMA_VERSION,
            self._to_checkpoint_payload(event), now, workflow_version=event.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
            workflow_version=checkpoint_event.workflow_version, checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
        ))

        logger.info(
            "action=pause_workflow status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "correlation_id=%s causation_id=%s",
            current.id, current.ticket_id, current.ticket_cycle_id, correlation_id, causation_id,
        )
        self._telemetry.record_workflow_paused(str(current.workflow_type))
        self._audit_recorder.record(
            "WORKFLOW_TRANSITION", "pause_workflow", "WorkflowInstance", str(current.id), "SUCCESS",
            workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=str(correlation_id), causation_id=str(causation_id),
        )
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

    def _to_checkpoint_payload(self, event: WorkflowPaused) -> str:
        """01-domain-model §"Checkpoint 怎么存": payloadJson must hold recoverable
        context — for a pause point, that is simply which generation and version the
        Workflow Instance stabilized at, enough for a resume (or a crash-recovery
        inspection) to confirm nothing moved underneath it while paused.
        """
        return json.dumps({
            "workflowInstanceId": str(event.workflow_instance_id),
            "pauseGeneration": event.pause_generation,
            "workflowVersion": event.workflow_version,
        })
