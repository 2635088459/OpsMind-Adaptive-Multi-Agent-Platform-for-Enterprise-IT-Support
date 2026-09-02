"""SPEC-ARO-039/041 (phase-10 Conversational Intake): SendMessageService — the inline
executor for `POST /api/v1/conversations/{conversationId}/messages`. Synchronously
creates, claims, and settles one `process_user_message` AgentTask entirely within this
request — bypassing the existing async claim/complete worker queue (domain-rules:
"the inline executor never uses the existing claim/complete async worker endpoints for
this task_type"). This task is deliberately never registered with
CoordinateAgentTasksService: SPEC-ARO-037's own conversational_intake task graph is
empty, so there is no downstream-unlock/settlement bookkeeping for an ad hoc,
one-per-turn task to participate in.

Three possible outcomes, matching SendMessageCommand's own discriminated response:
- "text": the task completes normally with the reply text as its result_payload.
- "proposed_action": the task enters AWAITING_USER_CONFIRMATION (SPEC-ARO-040's own new
  state) instead of completing — actually confirming/declining it is SPEC-ARO-040's own
  job, not built here.
- "escalation": the task completes, then the owning ticket is triaged for real
  (SPEC-ARO-041) and the Workflow Instance itself reaches COMPLETED (reusing
  CompleteWorkflowService unchanged) — "escalation is a workflow-instance-terminal
  event," never resumed to attempt further self-service on the same ticket.
"""

from __future__ import annotations

import dataclasses
import json
import logging
import uuid
from datetime import datetime, timedelta

from opentelemetry import trace

from agentruntime.application.commands import (
    CompleteWorkflowCommand,
    SendMessageCommand,
)
from agentruntime.application.exceptions import (
    ConversationAccessDeniedException,
    ConversationNotFoundException,
    EscalationRoutingNotConfiguredException,
    WorkflowNotRunningException,
)
from agentruntime.application.ports_out import (
    AgentTaskRepository,
    CheckpointRepository,
    ClockPort,
    CommandIdempotencyRepository,
    ConversationReasoningPort,
    KnowledgeRetrievalPort,
    TicketWorkflowClientPort,
    WorkflowInstanceRepository,
)
from agentruntime.application.records import (
    AgentTaskRecord,
    CheckpointRecord,
    ReasoningOutcome,
    WorkflowInstanceRecord,
)
from agentruntime.application.services.complete_workflow import CompleteWorkflowService
from agentruntime.application.services.conversational_intake import (
    CONVERSATIONAL_INTAKE_WORKFLOW_TYPE,
    PROCESS_USER_MESSAGE_TASK_TYPE,
)
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.views import MessageTurnView
from agentruntime.domain import agent_task, checkpoint
from agentruntime.domain.enums import CheckpointType, WorkflowState
from agentruntime.domain.ids import (
    AgentTaskId,
    CheckpointId,
    IdempotencyKey,
    LeaseToken,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

_CHECKPOINT_SCHEMA_VERSION = 1
_COMMAND_TYPE = "send_message"
_WORKER_ID = "conversation-inline-executor"
_LEASE_SECONDS = 30


class SendMessageService:
    def __init__(
        self,
        workflow_instance_repository: WorkflowInstanceRepository,
        agent_task_repository: AgentTaskRepository,
        checkpoint_repository: CheckpointRepository,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
        knowledge_retrieval_port: KnowledgeRetrievalPort,
        conversation_reasoning_port: ConversationReasoningPort,
        ticket_workflow_client: TicketWorkflowClientPort,
        complete_workflow_service: CompleteWorkflowService,
        escalation_category_id: str,
        escalation_support_queue_id: str,
        escalation_priority: str,
        escalation_team_name: str,
    ) -> None:
        self._workflow_instance_repository = workflow_instance_repository
        self._agent_task_repository = agent_task_repository
        self._checkpoint_repository = checkpoint_repository
        self._clock = clock
        self._knowledge_retrieval_port = knowledge_retrieval_port
        self._conversation_reasoning_port = conversation_reasoning_port
        self._ticket_workflow_client = ticket_workflow_client
        self._complete_workflow_service = complete_workflow_service
        self._escalation_category_id = escalation_category_id
        self._escalation_support_queue_id = escalation_support_queue_id
        self._escalation_priority = escalation_priority
        self._escalation_team_name = escalation_team_name
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def send_message(self, command: SendMessageCommand) -> MessageTurnView:
        request_payload = {
            "conversationId": str(command.conversation_id), "text": command.text,
            "attachmentRefs": list(command.attachment_refs),
        }
        return self._idempotency_guard.run(
            _COMMAND_TYPE, str(command.conversation_id), command.idempotency_key, request_payload,
            execute=lambda: self._send_message(command),
            to_dict=lambda view: view.to_dict(), from_dict=MessageTurnView.from_dict,
        )

    def _send_message(self, command: SendMessageCommand) -> MessageTurnView:
        with tracer.start_as_current_span("conversation.send_message"):
            return self._send_message_traced(command)

    def _send_message_traced(self, command: SendMessageCommand) -> MessageTurnView:
        workflow = self._workflow_instance_repository.find_by_id(command.conversation_id)
        if workflow is None or str(workflow.workflow_type) != CONVERSATIONAL_INTAKE_WORKFLOW_TYPE:
            raise ConversationNotFoundException(command.conversation_id)
        if workflow.requester_subject != command.requester_subject:
            raise ConversationAccessDeniedException()
        if workflow.state is not WorkflowState.RUNNING:
            raise WorkflowNotRunningException()

        now = self._clock.now()
        agent_task_id = AgentTaskId.new_id()
        task_key = f"message-{uuid.uuid4()}"

        create_event = agent_task.create(
            agent_task_id, workflow.id, workflow.state, PROCESS_USER_MESSAGE_TASK_TYPE, frozenset(), True, now,
        )
        created = AgentTaskRecord(
            id=agent_task_id, workflow_instance_id=workflow.id, task_key=task_key, task_type=PROCESS_USER_MESSAGE_TASK_TYPE,
            depends_on_task_keys=frozenset(), state=create_event.to_state, task_version=create_event.task_version,
            worker_id=None, lease_token=None, lease_expires_at=None, result_payload=None, failure_reason=None,
            pause_generation=workflow.pause_generation, created_at=now, updated_at=now,
        )
        self._agent_task_repository.save(created)

        lease_token = LeaseToken.new_token()
        lease_expires_at = now + timedelta(seconds=_LEASE_SECONDS)
        claim_event = agent_task.claim(
            agent_task_id, workflow.id, created.state, True, None, created.task_version, _WORKER_ID, lease_token,
            lease_expires_at, now,
        )
        claimed = dataclasses.replace(
            created, state=claim_event.to_state, task_version=claim_event.task_version, worker_id=_WORKER_ID,
            lease_token=lease_token, lease_expires_at=lease_expires_at, updated_at=now,
        )
        self._agent_task_repository.save(claimed)

        checkpoint_event = checkpoint.record(
            CheckpointId.new_id(), workflow.id, CheckpointType.PRE_KNOWLEDGE_RETRIEVAL, _CHECKPOINT_SCHEMA_VERSION,
            json.dumps({"text": command.text, "attachmentRefs": list(command.attachment_refs)}), now,
            workflow_version=workflow.workflow_version,
        )
        self._checkpoint_repository.save(CheckpointRecord(
            id=checkpoint_event.checkpoint_id, workflow_instance_id=checkpoint_event.workflow_instance_id,
            type=checkpoint_event.type, schema_version=checkpoint_event.schema_version, payload=checkpoint_event.payload,
            recorded_at=checkpoint_event.occurred_at, workflow_version=checkpoint_event.workflow_version,
            checksum=checkpoint_event.checksum, cursor=checkpoint_event.cursor,
        ))

        snippets = self._knowledge_retrieval_port.search(command.text, workflow.id, command.requester_subject)
        outcome = self._conversation_reasoning_port.decide(command.text, snippets)

        if outcome.kind == "proposed_action":
            return self._enter_awaiting_confirmation(claimed, outcome, now)
        if outcome.kind == "escalation":
            return self._escalate(workflow, claimed, command, outcome, now)
        return self._complete_as_text(claimed, outcome, now)

    def _complete_as_text(self, claimed: AgentTaskRecord, outcome: ReasoningOutcome, now: datetime) -> MessageTurnView:
        result_payload = json.dumps({"kind": "text", "text": outcome.text})
        complete_event = agent_task.complete(claimed.id, claimed.workflow_instance_id, claimed.state, claimed.task_version, result_payload, now)
        self._agent_task_repository.save(dataclasses.replace(
            claimed, state=complete_event.to_state, task_version=complete_event.task_version,
            result_payload=complete_event.result_payload, updated_at=now,
        ))
        logger.info("action=send_message status=completed outcome=text agent_task_id=%s", claimed.id)
        return MessageTurnView(kind="text", text=outcome.text)

    def _enter_awaiting_confirmation(self, claimed: AgentTaskRecord, outcome: ReasoningOutcome, now: datetime) -> MessageTurnView:
        awaiting_event = agent_task.await_user_confirmation(claimed.id, claimed.workflow_instance_id, claimed.state, claimed.task_version, now)
        # SPEC-ARO-040: ConfirmActionService needs the proposal's own summary/risk_level
        # later, and AgentTaskAwaitingUserConfirmation carries no extra fields of its
        # own (see that event's own docstring) — result_payload is reused here as the
        # durable slot for it, the same "an existing JSON slot outlives the field the
        # domain event itself doesn't carry" pattern this project's own domain 05
        # (tool-integration-gateway) used repeatedly. Not yet a "result" in the
        # completed sense — SendMessageService's own _complete_as_text()/_escalate()
        # write the exact same field once the task genuinely completes.
        pending_payload = json.dumps({
            "kind": "proposed_action", "actionSummary": outcome.action_summary, "actionRiskLevel": outcome.action_risk_level,
        })
        self._agent_task_repository.save(dataclasses.replace(
            claimed, state=awaiting_event.to_state, task_version=awaiting_event.task_version, updated_at=now,
            result_payload=pending_payload,
        ))
        logger.info("action=send_message status=awaiting_confirmation agent_task_id=%s", claimed.id)
        return MessageTurnView(
            kind="proposedAction", action_id=str(claimed.id), action_summary=outcome.action_summary,
            action_risk_level=outcome.action_risk_level,
        )

    def _escalate(
        self, workflow: WorkflowInstanceRecord, claimed: AgentTaskRecord, command: SendMessageCommand,
        outcome: ReasoningOutcome, now: datetime,
    ) -> MessageTurnView:
        if not self._escalation_category_id or not self._escalation_support_queue_id:
            raise EscalationRoutingNotConfiguredException()

        reason = outcome.escalation_reason or "The assistant determined this issue needs human assistance."
        triaged = self._ticket_workflow_client.triage_ticket(
            workflow.ticket_id, workflow.ticket_version, self._escalation_category_id, self._escalation_support_queue_id,
            self._escalation_priority, reason, str(command.idempotency_key),
        )

        result_payload = json.dumps({"kind": "escalation", "reason": reason})
        complete_event = agent_task.complete(claimed.id, claimed.workflow_instance_id, claimed.state, claimed.task_version, result_payload, now)
        self._agent_task_repository.save(dataclasses.replace(
            claimed, state=complete_event.to_state, task_version=complete_event.task_version,
            result_payload=complete_event.result_payload, updated_at=now,
        ))

        # Record the new real ticket_version before completing the Workflow Instance —
        # a distinct save (its own workflow_version bump), reused by
        # CompleteWorkflowService's own subsequent save immediately after.
        with_new_ticket_version = dataclasses.replace(
            workflow, ticket_version=triaged.version, workflow_version=workflow.workflow_version + 1, updated_at=now,
        )
        self._workflow_instance_repository.save(with_new_ticket_version)

        self._complete_workflow_service.complete(CompleteWorkflowCommand(
            workflow_instance_id=workflow.id, idempotency_key=_derive_escalation_complete_key(command.idempotency_key),
        ))

        logger.info(
            "action=send_message status=escalated agent_task_id=%s workflow_instance_id=%s ticket_id=%s",
            claimed.id, workflow.id, workflow.ticket_id,
        )
        return MessageTurnView(
            kind="escalation", ticket_id=str(workflow.ticket_id), display_id=workflow.ticket_display_id, reason=reason,
            assigned_team=self._escalation_team_name or "the support team",
        )


def _derive_escalation_complete_key(idempotency_key: IdempotencyKey) -> IdempotencyKey:
    """Mirrors start_conversation._derive_start_workflow_key()'s own reasoning: a
    distinct, deterministic key so CompleteWorkflowService's own CommandIdempotencyGuard
    never contends with this service's own guard over the same
    CommandIdempotencyRepository row.
    """
    return IdempotencyKey(f"{idempotency_key}:escalate-complete")
