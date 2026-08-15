"""13-package-and-class-design §"Application Layer": FailWorkflowService.
SPEC-ARO-004 Workflow Instance Aggregate: records the terminal FAILED transition with an
auditable reason (02-business-invariants: "failure paths must retain auditable reasons").
Mirrors CompleteWorkflowService's idempotency shape.
"""

from __future__ import annotations

import json
import logging
import uuid

from opentelemetry import trace

from agentruntime.application.commands import FailWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import (
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    OutboxRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import OutboxRecord, WorkflowInstanceRecord
from agentruntime.application.redaction import redact_payload
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import WorkflowInstanceView
from agentruntime.domain import workflow_instance
from agentruntime.domain.events import WorkflowFailed
from agentruntime.domain.ids import CausationId, CorrelationId

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

# SPEC-ARO-027 06-event-contracts §"workflow.failed.v1": see start_workflow.py's own
# _EVENT_TYPE comment (SPEC-ARO-025) for the full renaming rationale.
_EVENT_TYPE = "workflow.failed.v1"
_EVENT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "fail_workflow"


class FailWorkflowService:
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

    def fail(self, command: FailWorkflowCommand) -> WorkflowInstanceView:
        request_payload = {"workflowInstanceId": str(command.workflow_instance_id), "failureReason": command.failure_reason}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.workflow_instance_id), command.idempotency_key, request_payload,
            execute=lambda: self._fail(command),
            to_dict=lambda view: view.to_dict(), from_dict=WorkflowInstanceView.from_dict,
        )

    def _fail(self, command: FailWorkflowCommand) -> WorkflowInstanceView:
        with tracer.start_as_current_span("workflow.fail"):
            return self._fail_traced(command)

    def _fail_traced(self, command: FailWorkflowCommand) -> WorkflowInstanceView:
        current = self._workflow_instance_repository.find_by_id(command.workflow_instance_id)
        if current is None:
            raise WorkflowInstanceNotFoundException(command.workflow_instance_id)

        now = self._clock.now()
        event = workflow_instance.fail(
            command.workflow_instance_id, current.state, current.workflow_version, command.failure_reason, now
        )

        # SPEC-ARO-028: see CompleteWorkflowService's own identical comment — a read, not
        # a write, to advance current_checkpoint_id to whatever is most recent.
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
            "action=fail_workflow status=completed workflow_instance_id=%s ticket_id=%s ticket_cycle_id=%s "
            "failure_reason=%s correlation_id=%s causation_id=%s",
            current.id, current.ticket_id, current.ticket_cycle_id, command.failure_reason, correlation_id, causation_id,
        )
        duration_seconds = (now - current.created_at).total_seconds()
        self._telemetry.record_workflow_failed(str(current.workflow_type), duration_seconds)
        self._audit_recorder.record(
            "WORKFLOW_TRANSITION", "fail_workflow", "WorkflowInstance", str(current.id), "SUCCESS",
            workflow_instance_id=current.id, ticket_id=current.ticket_id,
            correlation_id=str(correlation_id), causation_id=str(causation_id),
            detail=redact_payload(json.dumps({"failure_reason": command.failure_reason})),
        )
        return WorkflowInstanceView.from_record(saved)

    def _to_payload(self, event: WorkflowFailed) -> str:
        return json.dumps({
            "workflowInstanceId": str(event.workflow_instance_id),
            "fromState": event.from_state.name if event.from_state else None,
            "toState": event.to_state.name,
            "workflowVersion": event.workflow_version,
            "failureReason": event.failure_reason,
            "occurredAt": event.occurred_at.isoformat(),
        })
