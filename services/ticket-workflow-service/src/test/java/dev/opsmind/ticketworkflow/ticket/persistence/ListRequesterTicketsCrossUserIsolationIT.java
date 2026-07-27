package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-003 acceptance "Other users' Tickets never appear": distinct from
 * {@link ListRequesterTicketsOwnershipIT}, this asserts the negative —
 * another employee's Ticket IDs are absent even when both employees own
 * Tickets in the same application and status.
 */
@Tag("integration")
class ListRequesterTicketsCrossUserIsolationIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldNeverIncludeAnotherEmployeesTicketId() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        UUID otherEmployeesTicket = seedTicket("employee-999", DEFAULT_APPLICATION_CODE, "NEW", now);
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(30));

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of());

        assertThat(itemTicketIds(bodyAsJson(response))).doesNotContain(otherEmployeesTicket);
    }

    @Test
    void shouldNotLeakAnotherEmployeesTicketEvenAtMaximumPageSize() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        for (int i = 0; i < 5; i++) {
            seedTicket("employee-999", DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(i));
        }
        UUID owned = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now.plusSeconds(10));

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of("limit", "50"));

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(owned);
    }
}
