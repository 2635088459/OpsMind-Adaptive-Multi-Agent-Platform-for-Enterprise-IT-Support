package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-002 §22: a pure query emits no business event or Outbox record. */
@Tag("integration")
class GetTicketDoesNotCreateOutboxIT extends AbstractGetTicketIT {

    @Test
    void shouldNotCreateOutboxEventForEmployeeRead() {
        UUID ticketId = seedTicket();

        ResponseEntity<String> response = getTicket(ticketId, employeeToken(DEFAULT_REQUESTER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.outbox_events")).isZero();
    }

    @Test
    void shouldNotCreateOutboxEventForSupportSensitiveRead() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        ResponseEntity<String> response = getTicket(ticketId, supportToken("support-100", List.of("HOUSING_PORTAL")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.outbox_events")).isZero();
    }
}
