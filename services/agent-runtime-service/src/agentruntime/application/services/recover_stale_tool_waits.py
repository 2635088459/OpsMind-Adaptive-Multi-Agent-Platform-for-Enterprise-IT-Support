"""13-package-and-class-design §"Application Layer": RecoverStaleToolWaitsService, the
sole implementation of ToolWaitRecoveryPort.

The gap this closes (found live 2026-09-09): a WAITING_TOOL Agent Task only ever leaves
that state on a real tool.completed/tool.failed delivery (02-business-invariants §"Tool
Gateway Boundary" / consume_tool_result.py). If the Tool Gateway never executes the
request — no dispatch worker runs as a process in this codebase — that delivery never
comes, the owning WAITING_FOR_TOOL Workflow Instance is stuck permanently, and
SendMessageService's `state is RUNNING` precondition means the conversation can never
send another message (409 forever). RecoverExpiredLeaseTasksService does not cover it:
its candidates are CLAIMED/RUNNING tasks with an expired lease, and a WAITING_TOOL task
holds neither.

This scan bounds the wait. Past `tool_wait_timeout_seconds` with no result it:
  1. fails the Tool Request (so a late tool.completed/tool.failed is a clean no-op —
     consume_tool_result.py already returns early for a non-PENDING/DISPATCHED request),
  2. fails the abandoned Agent Task via the existing domain.agent_task.fail_from_tool_result
     (WAITING_TOOL -> FAILED_FINAL) — the same transition consume_tool_result uses for a
     status-FAILED delivery,
  3. wakes the Workflow Instance via the existing domain.workflow_instance.wake_from_tool_wait
     (WAITING_FOR_TOOL -> RUNNING), so the conversation is usable again — the next message
     starts a fresh turn.

Deliberately checkpoint-free / outbox-free, mirroring RecoverExpiredLeaseTasksService's
own `_mark_stale()`: a recovery scan flips aggregate state and audits, it does not run
the AFTER_TASK checkpoint / task-graph settlement CompleteAgentTaskService does — those
belong to a legitimate task outcome, and settling a conversational_intake instance on a
single failed turn would FAIL the whole long-lived conversation, the opposite of the
goal. A lost race (a genuinely-late delivery landing between the scan read and this
save) is silently skipped, not an error.
"""

from __future__ import annotations

import dataclasses
import logging
from datetime import datetime, timedelta

from opentelemetry import trace

from agentruntime.application.exceptions import (
    AgentTaskVersionConflictException,
    WorkflowInstanceVersionConflictException,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    ClockPort,
    ToolRequestRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import AgentTaskRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import ToolWaitRecoveryReport
from agentruntime.domain import agent_task, workflow_instance
from agentruntime.domain.enums import ToolRequestStatus, WorkflowState
from agentruntime.domain.exceptions import (
    InvalidAgentTaskTransitionException,
    InvalidWorkflowTransitionException,
)

_DEFAULT_SCAN_BATCH_SIZE = 50
_STILL_WAITING_TOOL_REQUEST_STATUSES = frozenset({ToolRequestStatus.PENDING, ToolRequestStatus.DISPATCHED})

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class RecoverStaleToolWaitsService:
    def __init__(
        self,
        agent_task_repository: AgentTaskRepository,
        workflow_instance_repository: WorkflowInstanceRepository,
        tool_request_repository: ToolRequestRepository,
        clock: ClockPort,
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
        tool_wait_timeout_seconds: float,
    ) -> None:
        self._agent_task_repository = agent_task_repository
        self._workflow_instance_repository = workflow_instance_repository
        self._tool_request_repository = tool_request_repository
        self._clock = clock
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder
        self._timeout = timedelta(seconds=tool_wait_timeout_seconds)

    def scan_and_recover(self, batch_size: int = _DEFAULT_SCAN_BATCH_SIZE) -> ToolWaitRecoveryReport:
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            stale = self._agent_task_repository.find_stale_tool_waits(now - self._timeout, batch_size)
            timed_out = sum(1 for task in stale if self._time_out(task, now))
            return ToolWaitRecoveryReport(scanned=len(stale), timed_out=timed_out, scanned_at=now)

    def _time_out(self, task: AgentTaskRecord, now: datetime) -> bool:
        reason = f"tool wait timed out after {int(self._timeout.total_seconds())}s with no tool result"

        try:
            task_event = agent_task.fail_from_tool_result(
                task.id, task.workflow_instance_id, task.state, task.task_version, reason, now,
            )
        except (InvalidAgentTaskTransitionException, ValueError):
            return False
        try:
            self._agent_task_repository.save(dataclasses.replace(
                task, state=task_event.to_state, task_version=task_event.task_version,
                failure_reason=reason, updated_at=now,
            ))
        except AgentTaskVersionConflictException:
            # A concurrent scan (or a legitimately-late delivery) already moved this
            # task on between the find and the save — nothing more to do here.
            return False

        # Fail the Tool Request so a genuinely-late delivery is a clean no-op.
        tool_request = self._tool_request_repository.find_by_agent_task_id(task.id)
        if tool_request is not None and tool_request.status in _STILL_WAITING_TOOL_REQUEST_STATUSES:
            self._tool_request_repository.save(dataclasses.replace(
                tool_request, status=ToolRequestStatus.FAILED, result_payload=reason, updated_at=now,
            ))

        # Wake the workflow back to RUNNING so the conversation is usable again.
        workflow = self._workflow_instance_repository.find_by_id(task.workflow_instance_id)
        ticket_id = ticket_cycle_id = None
        if workflow is not None:
            ticket_id, ticket_cycle_id = workflow.ticket_id, workflow.ticket_cycle_id
            if workflow.state is WorkflowState.WAITING_FOR_TOOL:
                try:
                    wake_event = workflow_instance.wake_from_tool_wait(
                        workflow.id, workflow.state, workflow.workflow_version, now,
                    )
                    self._workflow_instance_repository.save(dataclasses.replace(
                        workflow, state=wake_event.to_state, workflow_version=wake_event.workflow_version, updated_at=now,
                    ))
                except (InvalidWorkflowTransitionException, WorkflowInstanceVersionConflictException):
                    pass  # moved on some other way (admin pause/cancel, or a concurrent scan) — leave it

        logger.info(
            "action=tool_wait_recovery_timeout agent_task_id=%s workflow_instance_id=%s ticket_id=%s "
            "ticket_cycle_id=%s timeout_seconds=%s",
            task.id, task.workflow_instance_id, ticket_id, ticket_cycle_id, int(self._timeout.total_seconds()),
        )
        self._telemetry.record_tool_wait_timed_out()
        self._audit_recorder.record(
            "RECOVERY_DECISION", "tool_wait_recovery_timeout", "AgentTask", str(task.id), "SUCCESS",
            workflow_instance_id=task.workflow_instance_id, ticket_id=ticket_id, actor_type="SYSTEM",
        )
        return True
