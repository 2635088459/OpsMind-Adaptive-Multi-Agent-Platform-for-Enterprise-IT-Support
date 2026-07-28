package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates a stale IN_PROGRESS idempotency reservation (e.g. from a
 * crashed prior attempt) by seeding it directly via SQL — the normal
 * single-transaction Add Ticket Message flow cannot produce this state
 * itself, since a failure rolls back the reservation along with
 * everything else. The service must reclaim a reservation older than the
 * 5-minute stale threshold and proceed, never leaving a second Message
 * behind.
 */
@Tag("integration")
class AddTicketMessageStaleIdempotencyIT extends AbstractAddTicketMessageIT {

    @Autowired
    private RequestHashCalculator requestHashCalculator;

    @Test
    void shouldReclaimStaleInProgressReservationAndCreateExactlyOneMessage() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");
        String idempotencyKey = UUID.randomUUID().toString();
        String actorScope = "user:" + DEFAULT_REQUESTER + ":" + ticketId + ":addTicketMessage";
        String requestHash = requestHashCalculator.calculate(
            "POST", "/api/v1/tickets/{ticketId}/messages", actorScope, canonicalBody(ticketId)
        );

        UUID staleRecordId = UUID.randomUUID();
        Instant staleCreatedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        jdbcTemplate.update("""
            INSERT INTO ticket.idempotency_records
                (idempotency_record_id, actor_scope, idempotency_key, operation_id, request_hash, status, created_at, expires_at)
            VALUES (?, ?, ?, 'addTicketMessage', ?, 'IN_PROGRESS', ?, ?)
            """,
            staleRecordId, actorScope, idempotencyKey, requestHash,
            Timestamp.from(staleCreatedAt), Timestamp.from(staleCreatedAt.plusSeconds(86_400))
        );

        ResponseEntity<String> response = addMessage(ticketId, employeeToken(DEFAULT_REQUESTER), idempotencyKey, employeeBody(DEFAULT_CONTENT));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(countRows("ticket.ticket_messages")).isEqualTo(1);

        Map<String, Object> record = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.idempotency_records WHERE idempotency_record_id = ?", staleRecordId
        );
        assertThat(record.get("status")).isEqualTo("COMPLETED");
    }

    private Map<String, Object> canonicalBody(UUID ticketId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId.toString());
        body.put("messageType", "PUBLIC_REQUESTER_MESSAGE");
        body.put("content", DEFAULT_CONTENT);
        return body;
    }
}
