"""SPEC-TG-022 "03 Agent Runtime Tool Contract" 06-event-contracts
§"workflow.cancelled.v1": "Purpose: when Runtime workflow is cancelled,
Gateway attempts to cancel associated pending/running Tool Requests." Named in
TG's own consumed-events list since SPEC-TG-001 but never implemented — no
consumer, no query to find affected requests. Not one of the seven
``application/`` filenames 13-package-and-class-design literally lists — added
the same way ``application.consume_policy_rule_changed`` extended that set for
its own event surface.

"Attempts to cancel" (not "guarantees") — reuses ``CancelToolRequestUseCase``
per affected request and skips (does not fail the whole batch) any that raise
``InvalidToolRequestTransitionException`` (a request sitting in a pre-QUEUED
state such as WAITING_APPROVAL has no cancel edge in 03-state-machine's own
transition table at all — a pre-existing limitation, not this spec's job to
extend).
"""

from __future__ import annotations

import uuid

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import CancelToolRequestCommand, ConsumeWorkflowCancelledCommand
from tool_gateway.application.ports_in import CancelToolRequestUseCase
from tool_gateway.domain.errors import InvalidToolRequestTransitionException
from tool_gateway.domain.ids import WorkflowInstanceId
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, ProcessedEventRepository, ToolRequestRepository

_CONSUMER_NAME = "workflow-cancelled-consumer"


class ConsumeWorkflowCancelledService:
    def __init__(
        self, tool_request_repository: ToolRequestRepository, cancel_port: CancelToolRequestUseCase,
        processed_event_repository: ProcessedEventRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._tool_request_repository = tool_request_repository
        self._cancel_port = cancel_port
        self._processed_event_repository = processed_event_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def consume_workflow_cancelled(self, command: ConsumeWorkflowCancelledCommand) -> int:
        """Returns the number of Tool Requests actually cancelled (or already
        pending cancellation)."""

        if self._processed_event_repository.is_processed(command.event_id, _CONSUMER_NAME):
            return 0

        workflow_instance_id = WorkflowInstanceId(uuid.UUID(command.workflow_instance_id))
        affected = self._tool_request_repository.find_non_terminal_by_workflow_instance(workflow_instance_id)
        cancelled_count = 0
        for tool_request in affected:
            try:
                self._cancel_port.cancel_tool_request(CancelToolRequestCommand(
                    tool_request_id=str(tool_request.tool_request_id), idempotency_key=f"workflow-cancelled:{command.event_id}",
                    requested_by="agent-runtime-orchestration", reason="workflow cancelled", correlation_id=command.correlation_id,
                ))
                cancelled_count += 1
            except InvalidToolRequestTransitionException:
                continue

        self._audit_recorder.record(
            action="workflow_cancelled_received", resource_type="WORKFLOW_INSTANCE", resource_id=command.workflow_instance_id,
            outcome=f"CANCELLED_{cancelled_count}_OF_{len(affected)}", actor_id="agent-runtime-orchestration",
            correlation_id=command.correlation_id,
        )
        self._processed_event_repository.mark_processed(command.event_id, _CONSUMER_NAME, self._clock.now())
        return cancelled_count
