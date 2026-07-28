package dev.opsmind.ticketworkflow.ticket.application.query;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;

import java.util.Objects;

public record SupportQueueQuery(
    ActorContext actor,
    SupportQueueScope scope,
    SupportQueueFilters filters,
    int limit,
    String cursor
) {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    public SupportQueueQuery {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(filters, "filters must not be null");
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new RequestValidationException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }
}
