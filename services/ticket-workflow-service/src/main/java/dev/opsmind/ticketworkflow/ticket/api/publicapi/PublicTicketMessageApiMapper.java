package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Resolves the actor-specific request split (SPEC-TW-004 §4): an Employee
 * request may never carry {@code messageType}, and a Support request must.
 * Author, author type, and visibility are always server-derived, never
 * read from the request body.
 */
@Component
public class PublicTicketMessageApiMapper {

    public AddTicketMessageCommand toCommand(
        TicketId ticketId,
        AddTicketMessageRequest request,
        ActorContext actor,
        Set<ApplicationCode> allowedApplicationCodes,
        String idempotencyKey,
        String correlationId,
        String commandId,
        Instant requestedAt
    ) {
        TicketMessageType messageType = resolveMessageType(actor, request);
        MessageContent content = toMessageContent(request.content());

        return new AddTicketMessageCommand(
            ticketId,
            messageType,
            content,
            actor,
            allowedApplicationCodes,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public AddTicketMessageResponse toResponse(AddTicketMessageResult result) {
        return new AddTicketMessageResponse(
            result.messageId().value(),
            result.ticketId().value(),
            result.messageType().name(),
            result.visibility().name(),
            result.authorType(),
            result.content(),
            result.createdAt(),
            result.version()
        );
    }

    private TicketMessageType resolveMessageType(ActorContext actor, AddTicketMessageRequest request) {
        boolean isEmployee = "EMPLOYEE".equals(actor.actorType());

        if (isEmployee) {
            if (request.messageType() != null) {
                throw new RequestValidationException("messageType must not be provided by an Employee");
            }
            return TicketMessageType.PUBLIC_REQUESTER_MESSAGE;
        }

        if (request.messageType() == null || request.messageType().isBlank()) {
            throw new RequestValidationException("messageType is required");
        }

        TicketMessageType messageType;
        try {
            messageType = TicketMessageType.valueOf(request.messageType());
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException("messageType must be PUBLIC_SUPPORT_MESSAGE or INTERNAL_SUPPORT_NOTE");
        }
        if (messageType != TicketMessageType.PUBLIC_SUPPORT_MESSAGE && messageType != TicketMessageType.INTERNAL_SUPPORT_NOTE) {
            throw new RequestValidationException("messageType must be PUBLIC_SUPPORT_MESSAGE or INTERNAL_SUPPORT_NOTE");
        }
        return messageType;
    }

    private MessageContent toMessageContent(String rawContent) {
        try {
            return MessageContent.of(rawContent);
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException(e.getMessage());
        }
    }
}
