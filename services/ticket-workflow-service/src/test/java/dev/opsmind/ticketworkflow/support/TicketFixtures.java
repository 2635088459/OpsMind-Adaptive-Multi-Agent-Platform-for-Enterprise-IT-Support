package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class TicketFixtures {

    public static final String DEFAULT_SUBJECT = "user-123";
    public static final String DEFAULT_CLIENT_ID = "employee-portal";
    public static final String CREATE_SCOPE = "tickets:create";

    private TicketFixtures() {
    }

    public static ActorContext employeeActor() {
        return employeeActor(DEFAULT_SUBJECT);
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, DEFAULT_CLIENT_ID, Set.of(CREATE_SCOPE));
    }

    public static ActorContext employeeActorWithoutCreateScope() {
        return new ActorContext("EMPLOYEE", DEFAULT_SUBJECT, DEFAULT_CLIENT_ID, Set.of());
    }

    public static CreateTicketCommand createTicketCommand() {
        return createTicketCommand(employeeActor(), UUID.randomUUID().toString());
    }

    public static CreateTicketCommand createTicketCommand(ActorContext actor, String idempotencyKey) {
        return new CreateTicketCommand(
            TicketTitle.of("Cannot sign in to Housing Portal"),
            TicketDescription.of("Duo keeps asking me to enroll again."),
            ApplicationCode.HOUSING_PORTAL,
            TicketSource.PORTAL,
            actor,
            idempotencyKey,
            "corr-" + UUID.randomUUID(),
            "cmd-" + UUID.randomUUID(),
            Instant.parse("2026-07-23T16:30:00Z")
        );
    }
}
