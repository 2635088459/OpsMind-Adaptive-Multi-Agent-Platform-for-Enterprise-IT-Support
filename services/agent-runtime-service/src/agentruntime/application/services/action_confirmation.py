"""SPEC-ARO-040 (phase-10 Conversational Intake): ActionConfirmationService — confirm
and decline for a `ProposedAction` produced by SPEC-ARO-039's `process_user_message`
task, once it entered AWAITING_USER_CONFIRMATION.

Which branch confirm takes is decided purely by the risk_level SPEC-ARO-039's own
reasoning already produced (domain-rules: "this spec never re-derives or overrides
that risk classification") — LOW/MEDIUM dispatches a real tool request and waits,
bounded, for real completion; HIGH/CRITICAL creates a real governance approval request
and never attempts the wait at all. Both are read back from the AgentTaskRecord's own
`result_payload`, reused as the durable slot for the pending proposal's own fields
while AWAITING_USER_CONFIRMATION (see SendMessageService._enter_awaiting_confirmation's
own docstring for why).

domain-rules: "the same actionId can never be confirmed or declined a second time with
a new real side effect — a repeat returns the current, real terminal state." Both
confirm/decline first check whether the task has already left AWAITING_USER_CONFIRMATION
(by a previous call, possibly under a different idempotency key) and, if so, render its
real current outcome honestly instead of attempting a fresh transition.
"""

from __future__ import annotations

import dataclasses
import json
import logging
import time
from datetime import datetime

from opentelemetry import trace

from agentruntime.application.commands import ConfirmActionCommand, DeclineActionCommand
from agentruntime.application.exceptions import (
    ActionNotAwaitingConfirmationException,
    ActionNotFoundException,
    ConversationAccessDeniedException,
    ConversationNotFoundException,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    GovernanceApprovalClientPort,
    ToolRequestRepository,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    ToolRequestRecord,
    WorkflowInstanceRecord,
)
from agentruntime.application.services.conversational_intake import (
    CONVERSATIONAL_INTAKE_WORKFLOW_TYPE,
)
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import ActionOutcomeView
from agentruntime.domain import agent_task, checkpoint, tool_request, workflow_instance
from agentruntime.domain.enums import AgentTaskState, CheckpointType, ToolRequestStatus
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    ToolRequestId,
    WorkflowInstanceId,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_CHECKPOINT_SCHEMA_VERSION = 1
_TOOL_NAME = "self_service_action"
"""SPEC-ARO-040: a placeholder tool name — no real tool catalog maps a proposed
action's own summary to a specific real tool yet (that mapping is future, real-LLM-era
territory, the same honest gap ConversationReasoningPort's own docstring names)."""

_CONFIRM_COMMAND_TYPE = "confirm_action"
_DECLINE_COMMAND_TYPE = "decline_action"
_HIGH_RISK_LEVELS = {"HIGH", "CRITICAL"}


class ActionConfirmationService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        agent_task_repository: AgentTaskRepository,
        checkpoint_repository: CheckpointRepository,
        tool_request_repository: ToolRequestRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        governance_approval_client: GovernanceApprovalClientPort,
        bounded_wait_timeout_seconds: float,
        bounded_wait_poll_interval_seconds: float,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._agent_task_repository = agent_task_repository
        self._checkpoint_repository = checkpoint_repository
        self._tool_request_repository = tool_request_repository
        self._clock = clock
        self._governance_approval_client = governance_approval_client
        self._bounded_wait_timeout_seconds = bounded_wait_timeout_seconds
        self._bounded_wait_poll_interval_seconds = bounded_wait_poll_interval_seconds
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def confirm_action(self, command: ConfirmActionCommand) -> ActionOutcomeView:
        request_payload = {"conversationId": str(command.conversation_id), "actionId": str(command.action_id)}
        return self._idempotency_guard.run(
            _CONFIRM_COMMAND_TYPE, str(command.action_id), command.idempotency_key, request_payload,
            execute=lambda: self._confirm(command),
            to_dict=lambda view: view.to_dict(), from_dict=ActionOutcomeView.from_dict,
        )

    def decline_action(self, command: DeclineActionCommand) -> ActionOutcomeView:
        request_payload = {"conversationId": str(command.conversation_id), "actionId": str(command.action_id)}
        return self._idempotency_guard.run(
            _DECLINE_COMMAND_TYPE, str(command.action_id), command.idempotency_key, request_payload,
            execute=lambda: self._decline(command),
            to_dict=lambda view: view.to_dict(), from_dict=ActionOutcomeView.from_dict,
        )

    def _confirm(self, command: ConfirmActionCommand) -> ActionOutcomeView:
        with tracer.start_as_current_span("conversation.confirm_action"):
            workflow, task = self._load_and_authorize(command.conversation_id, command.action_id, command.requester_subject)

            if task.state is not AgentTaskState.AWAITING_USER_CONFIRMATION:
                return _render_current_outcome(task)

            proposal = json.loads(task.result_payload) if task.result_payload else {}
            risk_level = proposal.get("actionRiskLevel") or "LOW"
            now = self._clock.now()

            if risk_level in _HIGH_RISK_LEVELS:
                return self._await_approval(workflow, task, risk_level, now)
            return self._dispatch_tool(workflow, task, proposal, now)

    def _decline(self, command: DeclineActionCommand) -> ActionOutcomeView:
        _workflow, task = self._load_and_authorize(command.conversation_id, command.action_id, command.requester_subject)
        if task.state is not AgentTaskState.AWAITING_USER_CONFIRMATION:
            return _render_current_outcome(task)

        now = self._clock.now()
        decline_event = agent_task.decline(task.id, task.workflow_instance_id, task.state, task.task_version, now)
        self._agent_task_repository.save(dataclasses.replace(
            task, state=decline_event.to_state, task_version=decline_event.task_version,
            result_payload=decline_event.result_payload, updated_at=now,
        ))
        logger.info("action=decline_action status=declined agent_task_id=%s", task.id)
        return ActionOutcomeView("declined")

    def _dispatch_tool(self, workflow: WorkflowInstanceRecord, task: AgentTaskRecord, proposal: dict, now: datetime) -> ActionOutcomeView:
        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), workflow.id, CheckpointType.PRE_TOOL_CALL, _CHECKPOINT_SCHEMA_VERSION,
            json.dumps({"actionSummary": proposal.get("actionSummary")}), now, workflow_version=workflow.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version, payload=checkpoint_event.payload,
            recorded_at=checkpoint_event.occurred_at, workflow_version=checkpoint_event.workflow_version,
            checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
        ))

        tool_request_id = ToolRequestId.new_id()
        tool_requested_event = tool_request.create(
            tool_request_id, workflow.id, task.id, checkpoint_event.checkpoint_id, _TOOL_NAME,
            json.dumps({"summary": proposal.get("actionSummary")}), now,
        )
        self._tool_request_repository.save(ToolRequestRecord(
            id=tool_requested_event.tool_request_id, workflow_instance_id=tool_requested_event.workflow_instance_id,
            agent_task_id=tool_requested_event.agent_task_id, preceding_checkpoint_id=tool_requested_event.preceding_checkpoint_id,
            tool_name=tool_requested_event.tool_name, request_payload=tool_requested_event.request_payload,
            status=ToolRequestStatus.PENDING, created_at=now, updated_at=now,
        ))

        dispatch_event = agent_task.dispatch_tool_from_confirmation(task.id, task.workflow_instance_id, task.state, task.task_version, now)
        self._agent_task_repository.save(dataclasses.replace(
            task, state=dispatch_event.to_state, task_version=dispatch_event.task_version, updated_at=now,
        ))

        workflow_event = workflow_instance.wait_for_tool(workflow.id, workflow.state, workflow.workflow_version, now)
        self._workflow_instance_repository.save(dataclasses.replace(
            workflow, state=workflow_event.to_state, workflow_version=workflow_event.workflow_version, updated_at=now,
        ))

        logger.info("action=confirm_action status=dispatched agent_task_id=%s tool_request_id=%s", task.id, tool_request_id)
        return self._bounded_wait(task.id)

    def _await_approval(self, workflow: WorkflowInstanceRecord, task: AgentTaskRecord, risk_level: str, now: datetime) -> ActionOutcomeView:
        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), workflow.id, CheckpointType.PRE_APPROVAL_REQUEST, _CHECKPOINT_SCHEMA_VERSION,
            json.dumps({"riskLevel": risk_level}), now, workflow_version=workflow.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version, payload=checkpoint_event.payload,
            recorded_at=checkpoint_event.occurred_at, workflow_version=checkpoint_event.workflow_version,
            checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
        ))

        approval_ref = self._governance_approval_client.request_approval(
            task.id, workflow.id, workflow.ticket_id, risk_level,
            "The employee confirmed a proposed action classified as high-risk; a human must approve it before it runs.",
        )

        awaiting_event = agent_task.await_approval_from_confirmation(task.id, task.workflow_instance_id, task.state, task.task_version, now)
        self._agent_task_repository.save(dataclasses.replace(
            task, state=awaiting_event.to_state, task_version=awaiting_event.task_version, updated_at=now,
        ))

        workflow_event = workflow_instance.wait_for_approval(workflow.id, workflow.state, workflow.workflow_version, now)
        self._workflow_instance_repository.save(dataclasses.replace(
            workflow, state=workflow_event.to_state, workflow_version=workflow_event.workflow_version, updated_at=now,
        ))

        logger.info(
            "action=confirm_action status=awaiting_approval agent_task_id=%s approval_request_id=%s",
            task.id, approval_ref.approval_request_id,
        )
        return ActionOutcomeView("awaiting-approval")

    def _bounded_wait(self, agent_task_id: AgentTaskId) -> ActionOutcomeView:
        """SPEC-ARO-040 domain-rules: "the bounded wait's timeout is a configurable
        value ... never an indefinite block" and "on timeout, the response states
        still-processing honestly; it never fabricates a done result." No real tool
        executor exists anywhere in this platform yet (LoggingToolGatewayPort only logs
        a dispatch, it never calls back) — in this environment the wait will
        realistically always exhaust its bound, which is the honest, expected outcome,
        not a bug; a real tool.completed.v1 delivery arriving mid-wait (SPEC-ARO-020,
        unchanged by this spec) is what lets this ever resolve to "done" before then.
        """
        deadline = time.monotonic() + self._bounded_wait_timeout_seconds
        while True:
            current = self._agent_task_repository.find_by_id(agent_task_id)
            if current is not None and current.state.is_terminal():
                return ActionOutcomeView("done")
            if time.monotonic() >= deadline:
                return ActionOutcomeView("still-processing")
            time.sleep(self._bounded_wait_poll_interval_seconds)

    def _load_and_authorize(
        self, conversation_id: WorkflowInstanceId, action_id: AgentTaskId, requester_subject: str,
    ) -> tuple[WorkflowInstanceRecord, AgentTaskRecord]:
        workflow = self._workflow_instance_repository.find_by_id(conversation_id)
        if workflow is None or str(workflow.workflow_type) != CONVERSATIONAL_INTAKE_WORKFLOW_TYPE:
            raise ConversationNotFoundException(conversation_id)
        if workflow.requester_subject != requester_subject:
            raise ConversationAccessDeniedException()

        task = self._agent_task_repository.find_by_id(action_id)
        if task is None or task.workflow_instance_id != conversation_id:
            raise ActionNotFoundException(action_id)
        return workflow, task


def _render_current_outcome(task: AgentTaskRecord) -> ActionOutcomeView:
    """SPEC-ARO-040 domain-rules: "a repeat returns the current, real terminal state" —
    the counterpart for a confirm/decline call arriving after the action already left
    AWAITING_USER_CONFIRMATION (by an earlier call, possibly under a different
    idempotency key).
    """
    if task.state is AgentTaskState.WAITING_TOOL:
        return ActionOutcomeView("still-processing")
    if task.state is AgentTaskState.WAITING_EXTERNAL:
        return ActionOutcomeView("awaiting-approval")
    if task.state.is_terminal():
        if task.result_payload and json.loads(task.result_payload).get("kind") == "declined":
            return ActionOutcomeView("declined")
        return ActionOutcomeView("done")
    raise ActionNotAwaitingConfirmationException(task.id, task.state)
