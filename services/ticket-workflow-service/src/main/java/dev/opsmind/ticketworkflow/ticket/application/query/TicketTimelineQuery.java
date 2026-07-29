package dev.opsmind.ticketworkflow.ticket.application.query;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Objects;
import java.util.Set;

public record TicketTimelineQuery(
    TicketId ticketId,
    ActorContext actor,
    Set<ApplicationCode> allowedApplicationCodes,
    int limit,
    String cursor
) {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    public TicketTimelineQuery {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        allowedApplicationCodes = allowedApplicationCodes == null ? Set.of() : Set.copyOf(allowedApplicationCodes);
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new RequestValidationException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }
}
