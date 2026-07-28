package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketWriteGuard;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class AddTicketMessageFixtures {

    public static final UUID DEFAULT_TICKET_ID = UUID.fromString("018f0f1e-7b31-7a00-8f42-31f9b25b1a91");
    public static final String DEFAULT_REQUESTER = "employee-123";
    public static final String DEFAULT_CONTENT = "I restarted the VPN client, but the error still appears.";
    public static final String EMPLOYEE_MESSAGE_SCOPE = "tickets:message:self";
    public static final String SUPPORT_PUBLIC_SCOPE = "tickets:message:public";
    public static final String SUPPORT_INTERNAL_SCOPE = "tickets:message:internal";

    private AddTicketMessageFixtures() {
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of(EMPLOYEE_MESSAGE_SCOPE));
    }

    public static ActorContext employeeActorWithoutScope(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of());
    }

    public static ActorContext supportActor(String subject, String... scopes) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(scopes));
    }

    public static TicketWriteGuard guard(UUID ticketId, String requesterId, ApplicationCode applicationCode, TicketStatus status) {
        return new TicketWriteGuard(TicketId.of(ticketId), requesterId, applicationCode, status);
    }

    public static AddTicketMessageCommand employeeCommand(UUID ticketId, ActorContext actor, String idempotencyKey) {
        return new AddTicketMessageCommand(
            TicketId.of(ticketId),
            TicketMessageType.PUBLIC_REQUESTER_MESSAGE,
            MessageContent.of(DEFAULT_CONTENT),
            actor,
            Set.of(),
            idempotencyKey,
            "corr-" + UUID.randomUUID(),
            "cmd-" + UUID.randomUUID(),
            Instant.parse("2026-07-25T18:30:00Z")
        );
    }

    public static AddTicketMessageCommand supportCommand(
        UUID ticketId, ActorContext actor, TicketMessageType messageType, Set<ApplicationCode> allowedApplicationCodes, String idempotencyKey
    ) {
        return new AddTicketMessageCommand(
            TicketId.of(ticketId),
            messageType,
            MessageContent.of(DEFAULT_CONTENT),
            actor,
            allowedApplicationCodes,
            idempotencyKey,
            "corr-" + UUID.randomUUID(),
            "cmd-" + UUID.randomUUID(),
            Instant.parse("2026-07-25T18:30:00Z")
        );
    }
}
