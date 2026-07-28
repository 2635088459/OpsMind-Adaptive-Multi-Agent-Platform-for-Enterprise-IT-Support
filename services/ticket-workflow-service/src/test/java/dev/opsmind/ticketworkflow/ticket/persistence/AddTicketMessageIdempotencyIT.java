package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-004 §11: same key + same payload replays; same key + different payload conflicts. */
@Tag("integration")
class AddTicketMessageIdempotencyIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldReplayOriginalResponseForSameKeyAndSamePayload() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<String> first = addMessage(ticketId, employeeToken(DEFAULT_REQUESTER), idempotencyKey, employeeBody(DEFAULT_CONTENT));
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> replay = addMessage(ticketId, employeeToken(DEFAULT_REQUESTER), idempotencyKey, employeeBody(DEFAULT_CONTENT));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(countRows("ticket.ticket_messages")).isEqualTo(1);
        assertThat(countRows("ticket.audit_records")).isEqualTo(1);
        assertThat(countRows("ticket.outbox_events")).isEqualTo(1);
    }

    @Test
    void shouldRejectSameKeyWithDifferentPayload() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<String> first = addMessage(ticketId, employeeToken(DEFAULT_REQUESTER), idempotencyKey, employeeBody(DEFAULT_CONTENT));
        assertThat(first.getStatusCode()).as(first.getBody()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), idempotencyKey, employeeBody("A completely different message.")
        );

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(countRows("ticket.ticket_messages")).isEqualTo(1);
    }
}
