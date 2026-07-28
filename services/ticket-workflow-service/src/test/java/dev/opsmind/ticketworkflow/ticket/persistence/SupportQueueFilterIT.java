package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-005 §9: status, priority, applicationCode, assignedTeam, assignedAgent, unassignedOnly, slaState, and date filters. */
@Tag("integration")
class SupportQueueFilterIT extends AbstractSupportQueueIT {

    // The query's evaluationTime is real Instant.now() (SystemClockAdapter), not a fixed literal, so
    // every fixture anchored to "recent" must be relative to real time to land in the intended SLA state.
    private static final Instant NOW = Instant.now().minusSeconds(600);

    @Test
    void shouldFilterByStatus() {
        UUID newTicket = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", NOW);
        seedTicket(DEFAULT_APPLICATION_CODE, "INVESTIGATING", NOW.minusSeconds(60));

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("status", "NEW")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(newTicket);
    }

    @Test
    void shouldFilterByPriority() {
        UUID criticalTicket = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", "CRITICAL", NOW);
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", "LOW", NOW.minusSeconds(60));

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("priority", "P1")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(criticalTicket);
    }

    @Test
    void shouldFilterByApplicationCode() {
        UUID housingTicket = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", NOW);
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, "EMAIL", "NEW", "MEDIUM", "TEAM-EMAIL", null, NOW.minusSeconds(60), "ACTIVE", NOW.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE, "EMAIL"), Set.of(DEFAULT_TEAM, "TEAM-EMAIL")),
            Map.of("applicationCode", DEFAULT_APPLICATION_CODE)
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(housingTicket);
    }

    @Test
    void shouldFilterByAssignedTeam() {
        UUID housingTeamTicket = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, null, NOW, "ACTIVE", NOW.plusSeconds(86400)
        );
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", "TEAM-OTHER", null, NOW.minusSeconds(60), "ACTIVE", NOW.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM, "TEAM-OTHER")),
            Map.of("assignedTeam", DEFAULT_TEAM)
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(housingTeamTicket);
    }

    @Test
    void shouldFilterByAssignedAgent() {
        UUID assignedTicket = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, "agent-200", NOW, "ACTIVE", NOW.plusSeconds(86400)
        );
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, "agent-300", NOW.minusSeconds(60), "ACTIVE", NOW.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", "IT_ADMIN", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)),
            Map.of("assignedAgent", "agent-200")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(assignedTicket);
    }

    @Test
    void shouldFilterByUnassignedOnly() {
        UUID unassignedTicket = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, null, NOW, "ACTIVE", NOW.plusSeconds(86400)
        );
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, "agent-200", NOW.minusSeconds(60), "ACTIVE", NOW.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("unassignedOnly", "true")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(unassignedTicket);
    }

    @Test
    void shouldRejectConflictingUnassignedOnlyAndAssignedAgent() {
        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)),
            Map.of("unassignedOnly", "true", "assignedAgent", "agent-200")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void shouldFilterBySlaStateBreached() {
        // createdAt precedes resolutionDueAt (satisfying ck_sla_time_order) while resolutionDueAt
        // itself stays safely before the real evaluationTime (Instant.now() at query time), so this
        // Ticket is genuinely BREACHED rather than merely appearing so under a stale fictional clock.
        UUID breachedTicket = seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, null,
            NOW.minusSeconds(3600), "ACTIVE", NOW.minusSeconds(1200)
        );
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", "MEDIUM", DEFAULT_TEAM, null,
            NOW.minusSeconds(60), "ACTIVE", NOW.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("slaState", "BREACHED")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(breachedTicket);
    }

    @Test
    void shouldFilterByCreatedFromAndCreatedTo() {
        UUID inRange = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-07-20T00:00:00Z"));
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-07-01T00:00:00Z"));
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-08-01T00:00:00Z"));

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)),
            Map.of("createdFrom", "2026-07-15T00:00:00Z", "createdTo", "2026-07-25T00:00:00Z")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(inRange);
    }
}
