package dev.opsmind.ticketworkflow.ticket.application.query;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Objects;
import java.util.Set;

public record GetTicketQuery(
    TicketId ticketId,
    ActorContext actor,
    Set<ApplicationCode> allowedApplicationCodes,
    String ifNoneMatch
) {

    public GetTicketQuery {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        allowedApplicationCodes = allowedApplicationCodes == null ? Set.of() : Set.copyOf(allowedApplicationCodes);
    }
}
