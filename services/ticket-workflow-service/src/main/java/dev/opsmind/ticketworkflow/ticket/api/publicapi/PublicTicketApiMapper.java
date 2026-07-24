package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PublicTicketApiMapper {

    public CreateTicketCommand toCommand(
        CreateTicketRequest request,
        ActorContext actor,
        String idempotencyKey,
        String correlationId,
        String commandId,
        Instant requestedAt
    ) {
        if (request.source() != TicketSource.PORTAL) {
            throw new RequestValidationException("source must be PORTAL for employee-submitted tickets");
        }

        return new CreateTicketCommand(
            TicketTitle.of(request.title()),
            TicketDescription.of(request.description()),
            request.applicationCode(),
            request.source(),
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public CreateTicketResponse toResponse(CreateTicketResult result) {
        return new CreateTicketResponse(
            result.ticketId().value(),
            result.displayId().value(),
            result.status().name(),
            result.createdAt(),
            result.version()
        );
    }
}
