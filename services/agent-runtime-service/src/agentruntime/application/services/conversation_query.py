"""SPEC-ARO-042 (phase-10 Conversational Intake): ConversationQueryService, the sole
implementation of ConversationQueryPort. Mirrors WorkflowQueryService's own shape: a
plain read of Runtime-owned state, no domain call, no write, no idempotency guard.

domain-rules: "a conversation belonging to a different employee is never returned" —
both methods enforce this the same way SendMessageService does (compare the caller's
own requester_subject against the stored one), never relying on the caller to have
already checked.
"""

from __future__ import annotations

from agentruntime.application.exceptions import (
    ConversationAccessDeniedException,
    ConversationNotFoundException,
)
from agentruntime.application.ports_out import WorkflowInstanceRepository
from agentruntime.application.records import WorkflowInstanceRecord
from agentruntime.application.services.conversational_intake import (
    CONVERSATIONAL_INTAKE_WORKFLOW_TYPE,
)
from agentruntime.application.views import ConversationDetailView
from agentruntime.domain.ids import WorkflowInstanceId, WorkflowType


class ConversationQueryService:
    def __init__(self, workflow_instance_repository: WorkflowInstanceRepository) -> None:
        self._workflow_instance_repository = workflow_instance_repository

    def find_conversation(self, conversation_id: WorkflowInstanceId, requester_subject: str) -> ConversationDetailView:
        record = self._workflow_instance_repository.find_by_id(conversation_id)
        return ConversationDetailView.from_record(_require_owned_conversation(record, conversation_id, requester_subject))

    def find_most_recent_conversation(self, requester_subject: str) -> ConversationDetailView:
        """SPEC-ARO-042 api-contract: supports domain 09's UC-EP-06 — a returning
        employee who does not already know their conversationId. Raises
        ConversationNotFoundException (rendered as a 404, per that spec's own "or a
        404/empty result if none exists") when the requester has started no
        conversation at all — an empty result, not an error condition specific to any
        one conversationId.
        """
        record = self._workflow_instance_repository.find_most_recent_by_requester_and_workflow_type(
            requester_subject, WorkflowType(CONVERSATIONAL_INTAKE_WORKFLOW_TYPE),
        )
        if record is None:
            raise ConversationNotFoundException("(none for this requester)")
        return ConversationDetailView.from_record(record)


def _require_owned_conversation(
    record: WorkflowInstanceRecord | None, conversation_id: WorkflowInstanceId, requester_subject: str,
) -> WorkflowInstanceRecord:
    if record is None or str(record.workflow_type) != CONVERSATIONAL_INTAKE_WORKFLOW_TYPE:
        raise ConversationNotFoundException(conversation_id)
    if record.requester_subject != requester_subject:
        raise ConversationAccessDeniedException()
    return record
