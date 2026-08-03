package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeResult;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class UserReplyApiMapper {

    public UserReplyAndResumeCommand toCommand(
        TicketId ticketId, UUID requestId, UserReplyRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new UserReplyAndResumeCommand(
            ticketId,
            requestId,
            toMessageContent(request.body()),
            request.attachmentIds(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public UserReplyResponse toResponse(UserReplyAndResumeResult result) {
        return new UserReplyResponse(
            result.ticketId().value(),
            result.requestId(),
            result.messageId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.answeredAt(),
            result.resumeApplied(),
            result.version()
        );
    }

    private MessageContent toMessageContent(String rawBody) {
        try {
            return MessageContent.of(rawBody);
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException(e.getMessage());
        }
    }
}
