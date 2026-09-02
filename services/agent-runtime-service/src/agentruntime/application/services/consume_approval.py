"""13-package-and-class-design §"Application Layer": ConsumeApprovalService.
SPEC-ARO-021 04-use-cases UC-03 "消费 approval.granted":
1. "Consumer 收到 approval.granted" — handled by ConsumeRuntimeEventService's own
   dedup/staleness gate before this service is ever reached.
2. "去重并根据 approvalRequestId 找到 Workflow Instance" — unlike Tool Request (SPEC-
   ARO-017), there is no Runtime-owned ApprovalRequest aggregate: 07-data-model has no
   approval_requests table, so approvalRequestId is carried through for audit/
   correlation only. The actual lookup key is the envelope's own workflowInstanceId,
   exactly like every other runtime event this service's caller already resolves.
3. "校验 workflow 正在 WAITING_FOR_APPROVAL" — the sole precondition/idempotency guard:
   with no local ApprovalRequest record whose own terminal state could otherwise signal
   "already resolved" the way ConsumeToolResultService's Tool Request status does, a
   workflow no longer WAITING_FOR_APPROVAL (already woken by a prior delivery of this
   same decision, or never in that wait to begin with) is treated as a no-op rather than
   an error — covering both "duplicate under a different eventId" and "stale/misrouted
   delivery" under the one check.
4. "写 checkpoint，恢复 planner context" — CheckpointType.RECOVERY_SNAPSHOT ("recorded as
   an explicit recovery snapshot ahead of a crash-recovery-sensitive step"): resuming
   automation after a human decision is exactly that step, and its own docstring's
   "recovery" wording is the closest match to this step's own "恢复 planner context"
   phrasing — unlike tool.completed's AFTER_TASK reuse, this spec is what first gives
   RECOVERY_SNAPSHOT a real writer. Written only on the approved path; a rejected
   decision ends the workflow, leaving no planner context to recover.
5. "将相关 Agent Task 标记为可继续" — deliberately NOT implemented: nothing in this
   codebase today ever transitions an Agent Task into WAITING_EXTERNAL
   (AgentTaskState's own docstring: "once an approval/verification/input wait exists" —
   no spec has built that entry path yet), so there is never a real task this step could
   find. Implementing it against a precondition nothing produces would be speculative,
   mirroring SPEC-ARO-020's own "no follow-up task creation" scope exclusion.
6. "Workflow 迁移回 RUNNING" — domain.workflow_instance.wake_from_approval_wait().

06-event-contracts documents exactly one contract, approval.granted.v1, with a
`decision` key field — no separate approval.rejected.v1 exists, and 03-state-machine's
own external-wake-up rule only pairs approval.granted with waking WAITING_FOR_APPROVAL.
Mirrors tool.completed.v1's own single-event-type-with-status-discriminator design:
decision == "APPROVED" wakes the workflow per UC-03's own six steps; any other value
fails it outright by reusing the existing FailWorkflowService (the codebase's one
authorized "fail a workflow" path — already idempotent via a deterministic
`approval-reject:{workflowInstanceId}` key, already outbox-publishing), with
decision/approvedBy folded into the auditable failure reason domain-rules requires.
UC-03 never describes a competing "denied" flow of its own, so this is the resolution of
that gap, not something the LLD spells out directly. No outbox event is published on the
approved path: unlike UC-04's explicit step 6 ("发布 agent.task.completed"), UC-03 names
no publish step of its own, and there is no existing "workflow woken from approval"
outbox-publishing service to reuse the way FailWorkflowService is reused for the reject
path — inventing one would be scope creep neither 04-use-cases nor 06-event-contracts
asks for.
"""

from __future__ import annotations

import json
from datetime import datetime

from agentruntime.application.commands import FailWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException
from agentruntime.application.ports_out import CheckpointRepository, ClockPort, WorkflowInstanceRepository
from agentruntime.application.records import CheckpointRecord, WorkflowInstanceRecord
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain import checkpoint, workflow_instance
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException, InvalidWorkflowTransitionException
from agentruntime.domain.ids import CheckpointId, IdempotencyKey, WorkflowInstanceId

_CHECKPOINT_SCHEMA_VERSION = 1
_APPROVED_DECISION = "APPROVED"


class ConsumeApprovalService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        checkpoint_repository: CheckpointRepository,
        clock: ClockPort,
        fail_workflow_service: FailWorkflowService,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._checkpoint_repository = checkpoint_repository
        self._clock = clock
        self._fail_workflow_service = fail_workflow_service

    def apply(self, workflow_instance_id: WorkflowInstanceId, payload_json: str) -> None:
        payload = json.loads(payload_json)
        approval_request_id: str = payload["approvalRequestId"]
        decision: str = payload["decision"]
        approved_by: str = payload["approvedBy"]

        workflow = self._workflow_instance_repository.find_by_id(workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(workflow_instance_id)
        if workflow.state is not WorkflowState.WAITING_FOR_APPROVAL:
            return  # UC-03 step 3: not (or no longer) waiting — duplicate or stale delivery

        now = self._clock.now()
        if decision == _APPROVED_DECISION:
            self._approve(workflow, approval_request_id, decision, approved_by, now)
        else:
            self._reject(workflow, approval_request_id, decision, approved_by, now)

    def _approve(
        self, workflow: WorkflowInstanceRecord, approval_request_id: str, decision: str, approved_by: str, now: datetime
    ) -> None:
        try:
            event = workflow_instance.wake_from_approval_wait(workflow.id, workflow.state, workflow.workflow_version, now)
        except InvalidWorkflowTransitionException:
            return  # lost a race against something else that already moved the workflow

        # SPEC-ARO-028: minted up front so the same save() below can point
        # current_checkpoint_id at it — no second write purely to record the pointer.
        checkpoint_id = CheckpointId.new_id()
        saved = self._workflow_instance_repository.save(WorkflowInstanceRecord(
            id=workflow.id, ticket_id=workflow.ticket_id, ticket_cycle_id=workflow.ticket_cycle_id,
            workflow_type=workflow.workflow_type, definition_id=workflow.definition_id, definition_version=workflow.definition_version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=workflow.pause_generation,
            current_checkpoint_id=checkpoint_id, completed_at=workflow.completed_at, created_at=workflow.created_at, updated_at=now,
            requester_subject=workflow.requester_subject, ticket_version=workflow.ticket_version,
            ticket_display_id=workflow.ticket_display_id,
        ))

        checkpoint_event = checkpoint.record(
            checkpoint_id, saved.id, CheckpointType.RECOVERY_SNAPSHOT, _CHECKPOINT_SCHEMA_VERSION,
            self._to_checkpoint_payload(saved.id, approval_request_id, decision, approved_by), now,
            workflow_version=saved.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version,
            payload=checkpoint_event.payload, recorded_at=checkpoint_event.occurred_at,
            workflow_version=checkpoint_event.workflow_version, checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
        ))

    def _reject(
        self, workflow: WorkflowInstanceRecord, approval_request_id: str, decision: str, approved_by: str, now: datetime
    ) -> None:
        failure_reason = f"approval request {approval_request_id} was not approved (decision={decision}, approvedBy={approved_by})"
        idempotency_key = IdempotencyKey(f"approval-reject:{workflow.id}")
        try:
            self._fail_workflow_service.fail(FailWorkflowCommand(workflow.id, idempotency_key, failure_reason))
        except InvalidWorkflowStateException:
            pass  # lost a race against something else that already moved the workflow to terminal

    def _to_checkpoint_payload(self, workflow_instance_id: WorkflowInstanceId, approval_request_id: str, decision: str, approved_by: str) -> str:
        return json.dumps({
            "workflowInstanceId": str(workflow_instance_id), "approvalRequestId": approval_request_id,
            "decision": decision, "approvedBy": approved_by,
        })
