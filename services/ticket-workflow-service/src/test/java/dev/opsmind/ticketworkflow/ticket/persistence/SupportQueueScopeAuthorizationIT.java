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

/**
 * SPEC-TW-005 §6/§7: resource authorization is a SQL predicate, not a
 * post-query Java filter — an out-of-scope Ticket never appears in
 * {@code items}, and requesting an out-of-scope filter is rejected before
 * any Ticket data is returned.
 */
@Tag("integration")
class SupportQueueScopeAuthorizationIT extends AbstractSupportQueueIT {

    @Test
    void shouldOnlyReturnTicketsWithinTheAuthorizedApplicationScope() {
        Instant now = Instant.parse("2026-07-25T19:00:00Z");
        UUID authorized = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now);
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, "VPN", "NEW", "MEDIUM", "TEAM-VPN", null, now.minusSeconds(60), "ACTIVE", now.plusSeconds(86400)
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(authorized);
    }

    @Test
    void shouldRejectApplicationCodeFilterOutsideAuthorizedScopeWithoutReturningData() {
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-07-25T19:00:00Z"));

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("applicationCode", "VPN")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FILTER_OUTSIDE_AUTHORIZED_SCOPE");
        assertThat(response.getBody()).doesNotContain(DEFAULT_APPLICATION_CODE);
    }

    @Test
    void shouldReturnEmptyQueueWhenActorHasNoAuthorizedApplications() {
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-07-25T19:00:00Z"));

        ResponseEntity<String> response = queryQueue(supportToken("support-100", Set.of(), Set.of()), Map.of());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(itemTicketIds(bodyAsJson(response))).isEmpty();
    }

    @Test
    void shouldReturn403ForEmployeeActorType() {
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", Instant.parse("2026-07-25T19:00:00Z"));

        ResponseEntity<String> response = queryQueue(
            supportToken("employee-123", "EMPLOYEE", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }
}
