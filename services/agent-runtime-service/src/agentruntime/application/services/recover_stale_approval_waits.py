"""13-package-and-class-design §"Application Layer": RecoverStaleApprovalWaitsService,
the sole implementation of ApprovalWaitRecoveryPort. SPEC-XREL-001.

The gap this closes: a WAITING_FOR_APPROVAL Workflow Instance only ever leaves that
state on an approval decision delivered through consume_approval.py (via the event-relay
sidecar consuming approval.granted/denied/expired.v1 off `opsmind.events`). If that
delivery never comes — the approval request was abandoned in governance, governance's
own expiry sweep never ran, or the relay was down for the whole approval TTL window —
the workflow is parked permanently and SendMessageService's `state is RUNNING`
precondition means the conversation 409s on every further message. This is the exact
WAITING_FOR_APPROVAL analogue of the WAITING_TOOL gap RecoverStaleToolWaitsService
already closes, and this service is deliberately shaped the same way.

Past `approval_wait_timeout_seconds` with no decision it fails the workflow through the
existing FailWorkflowService (the codebase's one authorized "fail a workflow" path —
already idempotent, already outbox-publishing `workflow.failed.v1`), under a
deterministic `approval-wait-timeout:{workflowInstanceId}` idempotency key. A late
approval.granted landing afterwards is a clean no-op: consume_approval.py already
returns early for a workflow no longer WAITING_FOR_APPROVAL.

Reuses WorkflowInstanceRepository.find_non_terminal (its "oldest updated_at first"
recovery-scan query, SPEC-ARO-028) rather than adding a dedicated
find_stale_approval_waits to both persistence backends — this is the same
low-frequency, admin/ops-triggered batch profile as the other recovery scans, and the
WAITING_FOR_APPROVAL + age filter is cheap in memory over one bounded page.

Deliberately checkpoint-free / no new domain event, exactly like
RecoverStaleToolWaitsService and RecoverExpiredLeaseTasksService: a recovery scan flips
terminal state and audits, it does not run the settlement a legitimate outcome would.
A lost race (a genuine decision landing between the scan read and the fail) surfaces as
FailWorkflowService raising, which is swallowed here as a no-op.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta

from opentelemetry import trace

from agentruntime.application.commands import FailWorkflowCommand
from agentruntime.application.exceptions import (
    WorkflowInstanceNotFoundException,
    WorkflowInstanceVersionConflictException,
)
from agentruntime.application.ports_out import ClockPort, WorkflowInstanceRepository
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.audit import AuditRecorder
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.application.telemetry import RuntimeTelemetry
from agentruntime.application.views import ApprovalWaitRecoveryReport
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException, InvalidWorkflowTransitionException
from agentruntime.domain.ids import IdempotencyKey

_DEFAULT_SCAN_BATCH_SIZE = 50

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class RecoverStaleApprovalWaitsService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        clock: ClockPort,
        fail_workflow_service: FailWorkflowService,
        telemetry: RuntimeTelemetry,
        audit_recorder: AuditRecorder,
        approval_wait_timeout_seconds: float,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock
        self._fail_workflow_service = fail_workflow_service
        self._telemetry = telemetry
        self._audit_recorder = audit_recorder
        self._timeout = timedelta(seconds=approval_wait_timeout_seconds)

    def scan_and_recover(self, batch_size: int = _DEFAULT_SCAN_BATCH_SIZE) -> ApprovalWaitRecoveryReport:
        with tracer.start_as_current_span("recovery.decision"):
            now = self._clock.now()
            cutoff = now - self._timeout
            stale = [
                workflow
                for workflow in self._workflow_instance_repository.find_non_terminal(batch_size)
                if workflow.state is WorkflowState.WAITING_FOR_APPROVAL and workflow.updated_at < cutoff
            ]
            timed_out = sum(1 for workflow in stale if self._time_out(workflow, now))
            return ApprovalWaitRecoveryReport(scanned=len(stale), timed_out=timed_out, scanned_at=now)

    def _time_out(self, workflow: WorkflowInstanceRecord, now: datetime) -> bool:
        reason = (
            f"approval wait timed out after {int(self._timeout.total_seconds())}s "
            "with no approval decision"
        )
        idempotency_key = IdempotencyKey(f"approval-wait-timeout:{workflow.id}")
        try:
            self._fail_workflow_service.fail(FailWorkflowCommand(workflow.id, idempotency_key, reason))
        except (
            InvalidWorkflowStateException,
            InvalidWorkflowTransitionException,
            WorkflowInstanceVersionConflictException,
            WorkflowInstanceNotFoundException,
        ):
            # Lost a race against a real approval decision (or a concurrent scan) that
            # already moved this workflow on between the find and the fail.
            return False

        logger.info(
            "action=approval_wait_recovery_timeout workflow_instance_id=%s ticket_id=%s "
            "ticket_cycle_id=%s timeout_seconds=%s",
            workflow.id, workflow.ticket_id, workflow.ticket_cycle_id, int(self._timeout.total_seconds()),
        )
        self._telemetry.record_approval_wait_timed_out()
        self._audit_recorder.record(
            "RECOVERY_DECISION", "approval_wait_recovery_timeout", "WorkflowInstance", str(workflow.id), "SUCCESS",
            workflow_instance_id=workflow.id, ticket_id=workflow.ticket_id, actor_type="SYSTEM",
        )
        return True
