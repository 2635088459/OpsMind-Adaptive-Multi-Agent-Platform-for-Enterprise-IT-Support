package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §1, Definition of Done: the Timeline is a pure query — it emits no Status History, Domain Event, or Outbox event. */
@Tag("integration")
class TicketTimelineDoesNotCreateOutboxIT extends AbstractTicketTimelineIT {

    @Test
    void shouldNotCreateOutboxEventsForAnEmployeeRead() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "hello", DEFAULT_CREATED_AT.plusSeconds(60));
        int outboxBefore = countRows("ticket.outbox_events");

        ResponseEntity<String> response = getTimeline(ticketId, employeeToken(DEFAULT_REQUESTER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(outboxBefore);
    }

    @Test
    void shouldNotCreateOutboxEventsForASupportInternalRead() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedInternalSupportNote(ticketId, "internal note", DEFAULT_CREATED_AT.plusSeconds(60));
        int outboxBefore = countRows("ticket.outbox_events");

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(outboxBefore);
    }
}
