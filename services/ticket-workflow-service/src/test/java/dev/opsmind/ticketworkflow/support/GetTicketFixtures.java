package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class GetTicketFixtures {

    public static final UUID DEFAULT_TICKET_ID = UUID.fromString("018f0f1e-7b31-7a00-8f42-31f9b25b1a91");
    public static final String EMPLOYEE_READ_SCOPE = "tickets:read:self";
    public static final String SUPPORT_READ_SCOPE = "tickets:read:queue";
    public static final String AUDITOR_READ_SCOPE = "tickets:audit:read";

    private GetTicketFixtures() {
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of(EMPLOYEE_READ_SCOPE));
    }

    public static ActorContext employeeActorWithoutReadScope(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of());
    }

    public static ActorContext supportActor(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(SUPPORT_READ_SCOPE));
    }

    public static ActorContext auditorActor(String subject) {
        return new ActorContext("AUDITOR", subject, "audit-console", Set.of(AUDITOR_READ_SCOPE));
    }

    public static GetTicketQuery employeeQuery(UUID ticketId, ActorContext actor) {
        return new GetTicketQuery(TicketId.of(ticketId), actor, Set.of(), null);
    }

    public static GetTicketQuery supportQuery(UUID ticketId, ActorContext actor, Set<ApplicationCode> allowedApplicationCodes) {
        return new GetTicketQuery(TicketId.of(ticketId), actor, allowedApplicationCodes, null);
    }

    public static EmployeeTicketProjection employeeProjection(UUID ticketId) {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        return new EmployeeTicketProjection(
            ticketId,
            "INC-2048",
            "Cannot sign in to Housing Portal",
            "Duo keeps asking me to enroll again.",
            "HOUSING_PORTAL",
            "PORTAL",
            "NEW",
            "UNASSIGNED",
            now,
            now,
            0L,
            "ACTIVE",
            now.plusSeconds(4 * 3600),
            now.plusSeconds(24 * 3600)
        );
    }

    public static SupportTicketProjection supportProjection(UUID ticketId, String requesterId) {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        return new SupportTicketProjection(
            ticketId,
            "INC-2048",
            "Cannot sign in to Housing Portal",
            "Duo keeps asking me to enroll again.",
            "HOUSING_PORTAL",
            "PORTAL",
            "NEW",
            "UNASSIGNED",
            requesterId,
            null,
            null,
            1,
            "ACTIVE",
            "ACTIVE",
            "SLA-STANDARD-P2",
            now.plusSeconds(4 * 3600),
            now.plusSeconds(24 * 3600),
            now,
            now,
            0L
        );
    }
}
