package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-003 §12: a pure query emits no business event or Outbox record. */
@Tag("integration")
class ListRequesterTicketsDoesNotCreateOutboxIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldNotCreateOutboxOrAuditRecordsWhenListing() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now);

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.outbox_events")).isZero();
        assertThat(countRows("ticket.audit_records")).isZero();
    }
}
