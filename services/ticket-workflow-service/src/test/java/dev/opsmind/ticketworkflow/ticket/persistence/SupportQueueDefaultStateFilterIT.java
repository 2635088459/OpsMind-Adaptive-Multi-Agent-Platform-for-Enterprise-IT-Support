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

/** SPEC-TW-005 §8: the default Queue excludes CLOSED/CANCELLED but keeps RESOLVED visible. */
@Tag("integration")
class SupportQueueDefaultStateFilterIT extends AbstractSupportQueueIT {

    @Test
    void shouldExcludeClosedAndCancelledButKeepResolvedByDefault() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        UUID newTicket = seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now);
        UUID resolvedTicket = seedTicket(DEFAULT_APPLICATION_CODE, "RESOLVED", now.minusSeconds(60));
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "CLOSED", "MEDIUM",
            DEFAULT_TEAM, null, now.minusSeconds(120), null, null
        );
        seedTicket(
            UUID.randomUUID(), DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "CANCELLED", "MEDIUM",
            DEFAULT_TEAM, null, now.minusSeconds(180), null, null
        );

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(itemTicketIds(bodyAsJson(response))).containsExactlyInAnyOrder(newTicket, resolvedTicket);
    }

    @Test
    void shouldRejectClosedInRequestedStatusFilter() {
        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of("status", "CLOSED")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }
}
