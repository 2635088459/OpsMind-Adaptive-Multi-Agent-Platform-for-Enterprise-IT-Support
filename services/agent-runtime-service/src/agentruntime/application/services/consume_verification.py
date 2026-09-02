"""13-package-and-class-design §"Application Layer": ConsumeVerificationService.
SPEC-ARO-022 04-use-cases UC-05 "消费 verification.completed":
1. "Consumer 收到 verification.completed" — handled by ConsumeRuntimeEventService's own
   dedup/staleness gate before this service is ever reached.
2. "根据 verificationRequestId 找到等待中的 Workflow" — mirrors ConsumeApprovalService:
   there is no Runtime-owned VerificationRequest aggregate (07-data-model has no
   verification_requests table, and 06-event-contracts' own key-fields list for
   verification.completed.v1 has no agentTaskId either — unlike tool.completed.v1, this
   event never touches any individual Agent Task). verificationRequestId is carried
   through for audit/correlation only; the lookup key is workflowInstanceId. "等待中"
   (waiting) doubles as the idempotency guard the same way SPEC-ARO-021 uses it: a
   workflow no longer WAITING_FOR_VERIFICATION is a no-op, not an error.
3. "如果 verification pass，Runtime 可进入完成路径" — 03-state-machine §"外部事件唤醒" is
   explicit that verification.completed *wakes* WAITING_FOR_VERIFICATION, the same verb
   used for tool.completed/approval.granted waking their own states — not "force-
   completes" it. So a passing verification wakes the workflow back to RUNNING via the
   new domain.workflow_instance.wake_from_verification_wait(), then — since verification
   touches no Agent Task, whether the task graph is now actually done is a separate
   question — reuses CoordinateAgentTasksService.determine_settlement() exactly the way
   ConsumeToolResultService's own _settle_workflow_if_done() does. "可进入完成路径" (Runtime
   *may* enter the completion path) reads precisely as "wakes, and completes if that
   happens to be the very last gate" — not an unconditional force-complete regardless of
   any Agent Task still outstanding.
4. "如果 verification fail，根据策略创建 remediation task 或失败" — no remediation-task
   policy mechanism exists in this codebase (mirrors SPEC-ARO-020's own "no follow-up
   task creation" and SPEC-ARO-021's own "no WAITING_EXTERNAL entry path" scope
   exclusions), so a failing verification always takes the "或失败" branch: fails the
   workflow directly via the existing FailWorkflowService, without waking to RUNNING
   first — mirrors ConsumeApprovalService's own reject path exactly, since a verification
   failure (unlike an in-progress task outcome) can never be reflected by the generic
   task-graph settlement check: the graph could show every Agent Task COMPLETED and
   determine_settlement() would still report success, oblivious to what verification
   itself decided.
5. "Runtime 发布 workflow.completed 或 workflow.failed" — both branches reuse
   CompleteWorkflowService/FailWorkflowService, the codebase's own sole
   authorized/outbox-publishing paths to those terminal states; nothing new to publish
   here.
"""

from __future__ import annotations

import json

from agentruntime.application.commands import CompleteWorkflowCommand, FailWorkflowCommand
from agentruntime.application.exceptions import WorkflowInstanceNotFoundException, WorkflowInstanceVersionConflictException
from agentruntime.application.ports_out import ClockPort, WorkflowInstanceRepository
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.coordinate_agent_tasks import CoordinateAgentTasksService
from agentruntime.application.services.fail_workflow import FailWorkflowService
from agentruntime.domain import workflow_instance
from agentruntime.domain.enums import WorkflowState
from agentruntime.domain.exceptions import InvalidWorkflowStateException, InvalidWorkflowTransitionException
from agentruntime.domain.ids import IdempotencyKey, WorkflowInstanceId


class ConsumeVerificationService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        clock: ClockPort,
        coordinate_agent_tasks_service: CoordinateAgentTasksService,
        complete_workflow_service: CompleteWorkflowService,
        fail_workflow_service: FailWorkflowService,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._clock = clock
        self._coordinate_agent_tasks_service = coordinate_agent_tasks_service
        self._complete_workflow_service = complete_workflow_service
        self._fail_workflow_service = fail_workflow_service

    def apply(self, workflow_instance_id: WorkflowInstanceId, payload_json: str) -> None:
        payload = json.loads(payload_json)
        verification_request_id: str = payload["verificationRequestId"]
        passed: bool = payload["passed"]
        evidence: str | None = payload.get("evidence")

        workflow = self._workflow_instance_repository.find_by_id(workflow_instance_id)
        if workflow is None:
            raise WorkflowInstanceNotFoundException(workflow_instance_id)
        if workflow.state is not WorkflowState.WAITING_FOR_VERIFICATION:
            return  # UC-05 step 2: not (or no longer) waiting — duplicate or stale delivery

        now = self._clock.now()
        if passed:
            self._pass(workflow, now)
        else:
            self._fail(workflow, verification_request_id, evidence, now)

    def _pass(self, workflow: WorkflowInstanceRecord, now) -> None:
        try:
            event = workflow_instance.wake_from_verification_wait(workflow.id, workflow.state, workflow.workflow_version, now)
        except InvalidWorkflowTransitionException:
            return  # lost a race against something else that already moved the workflow

        # SPEC-ARO-028: verification's own wake writes no checkpoint of its own, so
        # current_checkpoint_id/completed_at simply carry forward unchanged.
        awake = self._workflow_instance_repository.save(WorkflowInstanceRecord(
            id=workflow.id, ticket_id=workflow.ticket_id, ticket_cycle_id=workflow.ticket_cycle_id,
            workflow_type=workflow.workflow_type, definition_id=workflow.definition_id, definition_version=workflow.definition_version,
            state=event.to_state, workflow_version=event.workflow_version, pause_generation=workflow.pause_generation,
            current_checkpoint_id=workflow.current_checkpoint_id, completed_at=workflow.completed_at,
            created_at=workflow.created_at, updated_at=now,
            requester_subject=workflow.requester_subject, ticket_version=workflow.ticket_version,
            ticket_display_id=workflow.ticket_display_id,
        ))
        self._settle_workflow_if_done(awake.id, awake.state)

    def _fail(self, workflow: WorkflowInstanceRecord, verification_request_id: str, evidence: str | None, now) -> None:
        failure_reason = f"verification request {verification_request_id} did not pass"
        if evidence:
            failure_reason = f"{failure_reason} (evidence={evidence})"
        idempotency_key = IdempotencyKey(f"verification-reject:{workflow.id}")
        try:
            self._fail_workflow_service.fail(FailWorkflowCommand(workflow.id, idempotency_key, failure_reason))
        except InvalidWorkflowStateException:
            pass  # lost a race against something else that already moved the workflow to terminal

    def _settle_workflow_if_done(self, workflow_instance_id: WorkflowInstanceId, current_workflow_state: WorkflowState) -> None:
        """Mirrors ConsumeToolResultService's own _settle_workflow_if_done() verbatim —
        verification touches no Agent Task, so whether the task graph is actually done is
        answered the same generic way any other completion-adjacent event answers it.
        """
        if current_workflow_state.is_terminal():
            return

        settlement = self._coordinate_agent_tasks_service.determine_settlement(workflow_instance_id)
        if settlement is None:
            return

        idempotency_key = IdempotencyKey(f"auto-settle:{workflow_instance_id}")
        try:
            if settlement is WorkflowState.COMPLETED:
                self._complete_workflow_service.complete(CompleteWorkflowCommand(workflow_instance_id, idempotency_key))
            else:
                self._fail_workflow_service.fail(FailWorkflowCommand(
                    workflow_instance_id, idempotency_key, "one or more agent tasks in the task graph did not complete successfully"
                ))
        except (WorkflowInstanceVersionConflictException, InvalidWorkflowStateException):
            pass
