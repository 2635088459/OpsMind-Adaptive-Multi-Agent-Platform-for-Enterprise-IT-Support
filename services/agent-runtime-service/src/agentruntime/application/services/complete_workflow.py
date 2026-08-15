"""13-package-and-class-design §"Application Layer": CompleteWorkflowService.
SPEC-ARO-004 Workflow Instance Aggregate: records the terminal COMPLETED transition.
Mirrors ResumeWorkflowService's idempotency shape (no leniency for an already-terminal
instance) — CommandIdempotencyGuard returns the cached response for a repeated key; a
*new* key against an already-terminal workflow reaches domain.workflow_instance.complete(),
which rejects a terminal current_state and raises, and that natural domain guard is the
"conflict" outcome (re-completing a terminal workflow is a real error, unlike pause's
naturally-repeatable no-op).
"""

from __future__ import annotations

import json
import logging
import uuid

from opentelemetry import trace

from agentruntime.application.commands import CompleteWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import (
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    OutboxRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import workflow_instance
from agentruntime.domain.events import WorkflowCompleted
from agentruntime.domain.ids import CausationId, CorrelationId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# SPEC-ARO-027 06-event-contracts §"workflow.completed.v1": see start_workflow.py's own
# _EVENT_TYPE comment (SPEC-ARO-025) for the full renaming rationale — a straight rename,
# not an alias, since nothing before phase-07 ever published to a real broker under the
# old internal "agent_runtime.*" prefix.
_EVENT_TYPE = "workflow.completed.v1"
_EVENT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "complete_workflow"


class CompleteWorkflowService:
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

    def complete(self, command: CompleteWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {"workflowInstanceId": str(command.workflow_instance_id)}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.workflow_instance_id), command.idempotency_key, request_payload,
            execute=lambda: self._complete(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _complete(self, command: CompleteWorkflowCommand) -> WorkflowInstanceView:
        with tracer.start_as_current_span("workflow.complete"):
            return self._complete_traced(command)

    def _complete_traced(self, command: CompleteWorkflowCommand) -> WorkflowInstanceView:
        current = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if current is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        now = self._clock.now()
        event = workflow_instance.complete(command.workflow_instance_id, current.state, current.workflow_version, now)

        # SPEC-ARO-028: this service writes no checkpoint of its own, but a completed
        # workflow's own AFTER_TASK checkpoint (or STARTED, for a graph with no tasks at
        # all) is the "most recent stable checkpoint" 01-domain-model's own
        # currentCheckpointId describes — a read, not a write, so it costs nothing beyond
        # the query itself. Falls back to the previous pointer if, somehow, no checkpoint
        # is found (should never happen given the STARTED-checkpoint invariant, but this
        # service must not crash on it).
        latest_checkpoint = self._checkpoint_repository.find_latest_by_workflow_instance_id(current.id)
        current_checkpoint_id = latest_checkpoint.id if latest_checkpoint is not None else current.current_checkpoint_id

        updated_record = WorkflowInstanceRecord(
            id=current.id, ticket_id=current.ticket_id, ticket_cycle_id=current.ticket_cycle_id,
            workflow_type=current.workflow_type, definition_id=current.definition_id, definition_version=current.definition_version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=current.pause_generation,
            current_checkpoint_id=current_checkpoint_id, completed_at=now, created_at=current.created_at, updated_at=now,
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
            "action=complete_workflow status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "correlation_id=%s causation_id=%s",
            current.id, current.ticket_id, current.ticket_cycle_id, correlation_id, causation_id,
        )
        duration_seconds = (now - current.created_at).total_seconds()
        self._telemetry.record_workflow_completed(str(current.workflow_type), duration_seconds)
        self._audit_recorder.record(
            "WORKFLOW_TRANSITION", "complete_workflow", "WorkflowInstance", str(current.id), "SUCCESS",
            workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=str(correlation_id), causation_id=str(causation_id),
        )
        return WorkflowInstanceView.from_record(saved)

    def _to_payload(self, event: WorkflowCompleted) -> str:
        return json.dumps({
            "workflowInstanceId": str(event.workflow_instance_id),
            "fromState": event.from_state.name if event.from_state else None,
            "toState": event.to_state.name,
            "workflowVersion": event.workflow_version,
            "occurredAt": event.occurred_at.isoformat(),
        })
