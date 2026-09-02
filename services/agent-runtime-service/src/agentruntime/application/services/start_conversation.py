"""SPEC-ARO-038 (phase-10 Conversational Intake): StartConversationService — the entry
point every conversation goes through. For real, creates a ticket in
02-ticket-workflow, then creates a `conversational_intake` WorkflowInstance
(SPEC-ARO-037) bound to that real ticket, by calling StartWorkflowService directly
(the internal command, not the ticket-created event-ingestion path — the ticketId is
already known synchronously within this same request).

09-concurrency-and-idempotency: wrapped by its own CommandIdempotencyGuard — a repeat
of the same Idempotency-Key must never call 02-ticket-workflow's create-ticket
endpoint a second time (which would fabricate a duplicate real ticket) purely because
the caller retried after, say, a dropped response. The inner StartWorkflowService call
uses a *derived* idempotency key (see _derive_start_workflow_key()) rather than reusing
this command's own key verbatim: CommandIdempotencyRepository keys records by
idempotency_key alone, so two distinct guards (this service's own, and
StartWorkflowService's) claiming the same literal key would collide.
"""

from __future__ import annotations

from agentruntime.application.commands import (
    StartConversationCommand,
    StartWorkflowCommand,
)
from agentruntime.application.ports_out import (
    ClockPort,
    CommandIdempotencyRepository,
    TicketWorkflowClientPort,
)
from agentruntime.application.services.conversational_intake import (
    conversational_intake_definition,
)
from agentruntime.application.services.idempotency import CommandIdempotencyGuard
from agentruntime.application.services.start_workflow import StartWorkflowService
from agentruntime.application.views import ConversationView
from agentruntime.domain.ids import IdempotencyKey

_COMMAND_TYPE = "start_conversation"


class StartConversationService:
    def __init__(
        self,
        ticket_workflow_client: TicketWorkflowClientPort,
        start_workflow_service: StartWorkflowService,
        command_idempotency_repository: CommandIdempotencyRepository,
        clock: ClockPort,
    ) -> None:
        self._ticket_workflow_client = ticket_workflow_client
        self._start_workflow_service = start_workflow_service
        self._clock = clock
        self._idempotency_guard = CommandIdempotencyGuard(command_idempotency_repository, clock)

    def start_conversation(self, command: StartConversationCommand) -> ConversationView:
        """Named to match ConversationCommandPort exactly — this service is wired
        directly as that port's implementation (no separate adapter class), the same
        "service directly satisfies the port's Protocol shape" pattern
        RecoverWorkflowService/WorkflowQueryService/AgentTaskQueryService already use
        elsewhere in this container.
        """
        request_payload = {"requesterSubject": command.requester_subject}
        return self._idempotency_guard.run(
            _COMMAND_TYPE, command.requester_subject, command.idempotency_key, request_payload,
            execute=lambda: self._start(command),
            to_dict=lambda view: view.to_dict(), from_dict=ConversationView.from_dict,
        )

    def _start(self, command: StartConversationCommand) -> ConversationView:
        ticket_ref = self._ticket_workflow_client.create_ticket(command.forwarded_bearer_token, str(command.idempotency_key))

        definition = conversational_intake_definition()
        workflow_view = self._start_workflow_service.start(StartWorkflowCommand(
            ticket_id=ticket_ref.ticket_id, ticket_cycle_id=ticket_ref.ticket_cycle_id, definition=definition,
            idempotency_key=_derive_start_workflow_key(command.idempotency_key), requester_subject=command.requester_subject,
            ticket_version=ticket_ref.version, ticket_display_id=ticket_ref.display_id,
        ))

        return ConversationView(conversation_id=workflow_view.workflow_instance_id, started_at=workflow_view.updated_at)


def _derive_start_workflow_key(idempotency_key: IdempotencyKey) -> IdempotencyKey:
    """A deterministic, distinct key for the inner StartWorkflowCommand — a repeat of
    the outer command's own key always derives the same inner key, so the inner
    guard's own replay behavior stays correct across retries, without the two guards
    ever contending for the same CommandIdempotencyRepository row (that repository
    keys purely by idempotency_key, with no command_type component in its own key).
    """
    return IdempotencyKey(f"{idempotency_key}:conversational-intake")
