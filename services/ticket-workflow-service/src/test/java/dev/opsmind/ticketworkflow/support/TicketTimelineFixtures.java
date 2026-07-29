package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketTimelineViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineGuard;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItemType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TicketTimelineFixtures {

    public static final String EMPLOYEE_SCOPE = "tickets:read:self";
    public static final String SUPPORT_SCOPE = "tickets:read:queue";
    public static final String INTERNAL_SCOPE = TicketTimelineViewPolicy.INTERNAL_SCOPE;
    public static final String AUDITOR_SCOPE = "tickets:audit:timeline";
    public static final String DEFAULT_REQUESTER = "employee-123";
    public static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";

    private TicketTimelineFixtures() {
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of(EMPLOYEE_SCOPE));
    }

    public static ActorContext employeeActorWithoutScope(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of());
    }

    public static ActorContext supportPublicActor(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(SUPPORT_SCOPE));
    }

    public static ActorContext supportInternalActor(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(SUPPORT_SCOPE, INTERNAL_SCOPE));
    }

    public static ActorContext supportActorWithoutScope(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of());
    }

    public static ActorContext auditorActor(String subject) {
        return new ActorContext("AUDITOR", subject, "audit-console", Set.of(AUDITOR_SCOPE));
    }

    public static Set<ApplicationCode> scope(String... applicationCodes) {
        return java.util.Arrays.stream(applicationCodes).map(ApplicationCode::valueOf).collect(Collectors.toSet());
    }

    public static TicketTimelineGuard guard(String requesterId, String applicationCode) {
        return new TicketTimelineGuard("INC-2048", requesterId, applicationCode);
    }

    public static TicketTimelineItem ticketCreatedItem(UUID ticketId, Instant occurredAt) {
        return new TicketTimelineItem(
            "TICKET_CREATED:" + ticketId, TicketTimelineItemType.TICKET_CREATED, "PUBLIC", occurredAt,
            "EMPLOYEE", DEFAULT_REQUESTER, null, null, null, null, null, null, 0L
        );
    }

    public static TicketTimelineItem statusChangedItem(UUID historyId, Instant occurredAt, String fromStatus, String toStatus) {
        return new TicketTimelineItem(
            "STATUS_HISTORY:" + historyId, TicketTimelineItemType.STATUS_CHANGED, "PUBLIC", occurredAt,
            "IT_SUPPORT", "support-100", fromStatus, toStatus, "SM-003", "INVESTIGATION_STARTED", null, null, 1L
        );
    }

    public static TicketTimelineItem publicRequesterMessageItem(UUID messageId, Instant occurredAt, String content) {
        return new TicketTimelineItem(
            "MESSAGE:" + messageId, TicketTimelineItemType.PUBLIC_REQUESTER_MESSAGE, "PUBLIC", occurredAt,
            "EMPLOYEE", DEFAULT_REQUESTER, null, null, null, null, "PUBLIC_REQUESTER_MESSAGE", content, 0L
        );
    }

    public static TicketTimelineItem internalSupportNoteItem(UUID messageId, Instant occurredAt, String content) {
        return new TicketTimelineItem(
            "MESSAGE:" + messageId, TicketTimelineItemType.INTERNAL_SUPPORT_NOTE, "INTERNAL", occurredAt,
            "IT_SUPPORT", "support-100", null, null, null, null, "INTERNAL_SUPPORT_NOTE", content, 0L
        );
    }
}
