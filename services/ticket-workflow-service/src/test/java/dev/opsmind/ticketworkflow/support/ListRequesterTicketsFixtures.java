package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class ListRequesterTicketsFixtures {

    public static final String LIST_READ_SCOPE = "tickets:read:self";

    private ListRequesterTicketsFixtures() {
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of(LIST_READ_SCOPE));
    }

    public static ActorContext employeeActorWithoutReadScope(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of());
    }

    public static RequesterTicketSummary summary(UUID ticketId, Instant createdAt) {
        return new RequesterTicketSummary(
            ticketId,
            "INC-2048",
            "Cannot sign in to Housing Portal",
            "HOUSING_PORTAL",
            "NEW",
            "UNASSIGNED",
            createdAt,
            createdAt,
            0L
        );
    }

    public static TicketListFilters noFilters() {
        return TicketListFilters.none();
    }
}
