package dev.opsmind.ticketworkflow.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.query.SlaQueueState;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueuePriority;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class SupportQueueFixtures {

    public static final String QUEUE_READ_SCOPE = "tickets:read:queue";
    public static final String DEFAULT_APPLICATION_CODE = "HOUSING_PORTAL";
    public static final String DEFAULT_TEAM = "TEAM-HOUSING";

    private SupportQueueFixtures() {
    }

    public static ActorContext supportActor(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of(QUEUE_READ_SCOPE));
    }

    public static ActorContext supportActorWithoutScope(String subject) {
        return new ActorContext("IT_SUPPORT", subject, "support-console", Set.of());
    }

    public static ActorContext adminActor(String subject) {
        return new ActorContext("IT_ADMIN", subject, "support-console", Set.of(QUEUE_READ_SCOPE));
    }

    public static ActorContext employeeActor(String subject) {
        return new ActorContext("EMPLOYEE", subject, "employee-portal", Set.of(QUEUE_READ_SCOPE));
    }

    public static SupportQueueScope scope(String... applicationCodes) {
        Set<ApplicationCode> codes = java.util.Arrays.stream(applicationCodes).map(ApplicationCode::valueOf)
            .collect(java.util.stream.Collectors.toSet());
        return new SupportQueueScope(codes, Set.of(DEFAULT_TEAM));
    }

    public static SupportQueueScope scopeWithTeams(Set<String> applicationCodes, Set<String> teamIds) {
        Set<ApplicationCode> codes = applicationCodes.stream().map(ApplicationCode::valueOf).collect(java.util.stream.Collectors.toSet());
        return new SupportQueueScope(codes, teamIds);
    }

    public static SupportQueueFilters noFilters() {
        return new SupportQueueFilters(Set.of(), Set.of(), Set.of(), Set.of(), null, false, Set.of(), null, null);
    }

    public static SupportTicketSummary summary(UUID ticketId, Instant createdAt) {
        return new SupportTicketSummary(
            ticketId,
            "INC-2048",
            "Cannot sign in to Housing Portal",
            DEFAULT_APPLICATION_CODE,
            "INVESTIGATING",
            SupportQueuePriority.P2,
            "employee-123",
            DEFAULT_TEAM,
            null,
            true,
            SlaQueueState.ACTIVE,
            createdAt.plusSeconds(3600),
            createdAt.plusSeconds(86400),
            2,
            1,
            createdAt,
            createdAt,
            0L
        );
    }
}
