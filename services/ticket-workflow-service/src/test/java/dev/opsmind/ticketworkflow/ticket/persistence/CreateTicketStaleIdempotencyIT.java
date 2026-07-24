package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
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
 * Simulates a stale IN_PROGRESS idempotency reservation (e.g. from a crashed
 * prior attempt) by seeding it directly via SQL — the normal single-
 * transaction Create Ticket flow cannot produce this state itself, since a
 * failure rolls back the reservation along with everything else. The
 * service must reclaim a reservation older than the 5-minute stale
 * threshold and proceed, never leaving a second Ticket behind.
 */
@Tag("integration")
class CreateTicketStaleIdempotencyIT extends AbstractCreateTicketIT {

    @Autowired
    private RequestHashCalculator requestHashCalculator;

    @Test
    void shouldReclaimStaleInProgressReservationAndCreateExactlyOneTicket() {
        String subject = "user-stale-1";
        String idempotencyKey = newIdempotencyKey();
        String actorScope = "user:" + subject + ":createTicket";
        String requestHash = requestHashCalculator.calculate(
            "POST", "/api/v1/tickets", actorScope, canonicalBodyForDefaultRequest()
        );

        UUID staleRecordId = UUID.randomUUID();
        Instant staleCreatedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        jdbcTemplate.update("""
            INSERT INTO ticket.idempotency_records
                (idempotency_record_id, actor_scope, idempotency_key, operation_id, request_hash, status, created_at, expires_at)
            VALUES (?, ?, ?, 'createTicket', ?, 'IN_PROGRESS', ?, ?)
            """,
            staleRecordId, actorScope, idempotencyKey, requestHash,
            Timestamp.from(staleCreatedAt), Timestamp.from(staleCreatedAt.plusSeconds(86_400))
        );

        ResponseEntity<String> response = createTicket(subject, idempotencyKey, validRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countRows("ticket.tickets")).isEqualTo(1);

        Map<String, Object> record = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.idempotency_records WHERE idempotency_record_id = ?", staleRecordId
        );
        assertThat(record.get("status")).isEqualTo("COMPLETED");
    }

    private Map<String, Object> canonicalBodyForDefaultRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Cannot sign in to Housing Portal");
        body.put("description", "Duo keeps asking me to enroll again.");
        body.put("applicationCode", "HOUSING_PORTAL");
        body.put("source", "PORTAL");
        return body;
    }
}
