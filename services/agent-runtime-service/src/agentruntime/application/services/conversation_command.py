"""Sole implementation of ConversationCommandPort: a thin facade that composes the
three named conversation services, mirroring WorkflowCommandService's own composition
pattern exactly. Contains no business rules of its own.
"""

from __future__ import annotations

from agentruntime.application.commands import (
    ConfirmActionCommand,
    DeclineActionCommand,
    SendMessageCommand,
    StartConversationCommand,
)
from agentruntime.application.services.action_confirmation import (
    ActionConfirmationService,
)
from agentruntime.application.services.send_message import SendMessageService
from agentruntime.application.services.start_conversation import (
    StartConversationService,
)
from agentruntime.application.views import (
    ActionOutcomeView,
    ConversationView,
    MessageTurnView,
)


class ConversationCommandService:
    def __init__(
        self,
        start_conversation_service: StartConversationService,
        send_message_service: SendMessageService,
        action_confirmation_service: ActionConfirmationService,
    ) -> None:
        self._start_conversation_service = start_conversation_service
        self._send_message_service = send_message_service
        self._action_confirmation_service = action_confirmation_service

    def start_conversation(self, command: StartConversationCommand) -> ConversationView:
        return self._start_conversation_service.start_conversation(command)

    def send_message(self, command: SendMessageCommand) -> MessageTurnView:
        return self._send_message_service.send_message(command)

    def confirm_action(self, command: ConfirmActionCommand) -> ActionOutcomeView:
        return self._action_confirmation_service.confirm_action(command)

    def decline_action(self, command: DeclineActionCommand) -> ActionOutcomeView:
        return self._action_confirmation_service.decline_action(command)
