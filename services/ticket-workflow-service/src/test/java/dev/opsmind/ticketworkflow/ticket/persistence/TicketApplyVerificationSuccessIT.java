package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.configuration.RabbitMqConfiguration;
import dev.opsmind.ticketworkflow.support.AbstractTicketAssignmentIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SPEC-TW-023 persistence/E2E: full Spring context + real PostgreSQL and
 * RabbitMQ Testcontainers, publishing a real {@code verification.completed.v1}
 * (result = SUCCESS) message and letting the real {@code @RabbitListener}
 * consume it. The ticket is seeded directly in {@code VERIFYING} with an
 * {@code ACTIVE} {@code ticket_verification_attempts} row (SPEC-TW-022) —
 * the attempt this event must match.
 */
@Tag("integration")
class TicketApplyVerificationSuccessIT extends AbstractTicketAssignmentIT {

    private static final String ALLOWED_PRODUCER = "verification-service";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private UUID seedVerifyingTicketWithActiveAttempt(String verificationId, String workflowId, int attemptNumber) {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'VERIFYING' WHERE ticket_id = ?", ticketId);
        UUID resolutionCycleId = jdbcTemplate.queryForObject(
            "SELECT current_resolution_cycle_id FROM ticket.tickets WHERE ticket_id = ?", UUID.class, ticketId
        );
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_verification_attempts
                (verification_id, ticket_id, resolution_cycle_id, workflow_id, tool_result_id, attempt_number,
                 attempt_status, verification_type, started_at)
            VALUES (?, ?, ?, ?, 'tool-result-900', ?, 'ACTIVE', 'IDENTITY_LOGIN_CHECK', ?)
            """,
            verificationId, ticketId, resolutionCycleId, workflowId, attemptNumber, Timestamp.from(SEED_NOW)
        );
        return ticketId;
    }

    private UUID resolutionCycleId(UUID ticketId) {
        return jdbcTemplate.queryForObject("SELECT current_resolution_cycle_id FROM ticket.tickets WHERE ticket_id = ?", UUID.class, ticketId);
    }

    private String envelope(String eventId, String producer, String ticketId, String payload) {
        return """
            {
              "eventId": "%s",
              "eventType": "verification.completed",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-08T17:55:00Z",
              "producer": "%s",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": %s
            }
            """.formatted(eventId, producer, ticketId, payload);
    }

    private String successPayload(String workflowId, UUID resolutionCycleId, String verificationId, int attemptNumber, Instant completedAt) {
        return """
            {
              "workflowId": "%s",
              "resolutionCycleId": "%s",
              "verificationId": "%s",
              "attemptNumber": %d,
              "result": "SUCCESS",
              "verificationEvidenceId": "evidence-900",
              "evidenceSummary": {"checkType": "LOGIN_TEST", "resultCode": "AUTHENTICATION_SUCCEEDED"},
              "completedAt": "%s"
            }
            """.formatted(workflowId, resolutionCycleId, verificationId, attemptNumber, completedAt);
    }

    private void publish(String body) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, "verification.completed.v1", body);
    }

    @Test
    void shouldApplyVerificationSuccessAndMarkTheAttemptSucceeded() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);

        Instant completedAt = Instant.parse("2026-08-08T17:55:00Z");
        publish(envelope("evt-verification-1", ALLOWED_PRODUCER, ticketId.toString(), successPayload("wf-9000", resolutionCycleId, "ver-1234", 1, completedAt)));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> ticketRow = ticketRow(ticketId);
            assertThat(ticketRow.get("status")).isEqualTo("VERIFYING");
            assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);
        });

        Map<String, Object> attemptRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_verification_attempts WHERE verification_id = ?", "ver-1234"
        );
        assertThat(attemptRow.get("attempt_status")).isEqualTo("SUCCEEDED");
        assertThat(attemptRow.get("verification_evidence_id")).isEqualTo("evidence-900");
        assertThat(attemptRow.get("completed_event_id")).isEqualTo("evt-verification-1");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-026'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.verification-success-applied'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldBeIdempotentOnADuplicateDelivery() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        String body = envelope(
            "evt-verification-dup", ALLOWED_PRODUCER, ticketId.toString(),
            successPayload("wf-9000", resolutionCycleId, "ver-1234", 1, Instant.parse("2026-08-08T17:55:00Z"))
        );

        publish(body);
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L)
        );

        publish(body);
        await().pollDelay(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.verification-success-applied'", Integer.class, ticketId
            );
            assertThat(outboxCount).isEqualTo(1);
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void shouldFlagConflictRequiresReconciliationWhenAFailedResultAlreadyExists() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        jdbcTemplate.update("UPDATE ticket.ticket_verification_attempts SET attempt_status = 'FAILED' WHERE verification_id = ?", "ver-1234");

        publish(envelope(
            "evt-verification-late", ALLOWED_PRODUCER, ticketId.toString(),
            successPayload("wf-9000", resolutionCycleId, "ver-1234", 1, Instant.parse("2026-08-08T17:56:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> attemptRow = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket.ticket_verification_attempts WHERE verification_id = ?", "ver-1234"
            );
            assertThat(attemptRow.get("attempt_status")).isEqualTo("CONFLICT");
            assertThat(attemptRow.get("conflict_event_id")).isEqualTo("evt-verification-late");
        });

        // The ticket status/version is left untouched by the conflict path.
        Map<String, Object> ticketRow = ticketRow(ticketId);
        assertThat(ticketRow.get("status")).isEqualTo("VERIFYING");
        assertThat(((Number) ticketRow.get("version")).longValue()).isZero();
    }

    @Test
    void shouldSendAWrongProducerMessageToTheDeadLetterQueueWithoutAdvancingTheTicket() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-wrong-producer", "untrusted-service", ticketId.toString(),
            successPayload("wf-9000", resolutionCycleId, "ver-1234", 1, Instant.parse("2026-08-08T17:55:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
    }

    @Test
    void shouldSendASchemaInvalidMessageToTheDeadLetterQueue() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200);

        String invalidPayload = """
            {"workflowId": "wf-9000", "verificationId": "ver-1234"}
            """;
        publish(envelope("evt-invalid-schema", ALLOWED_PRODUCER, ticketId.toString(), invalidPayload));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
    }

    @Test
    void shouldSendAFailureResultToTheDeadLetterQueueSinceThisEventTypeOnlyAcceptsSuccess() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200);

        String failurePayload = successPayload("wf-9000", resolutionCycleId, "ver-1234", 1, Instant.parse("2026-08-08T17:55:00Z"))
            .replace("\"result\": \"SUCCESS\"", "\"result\": \"FAILURE\"");
        publish(envelope("evt-wrong-result", ALLOWED_PRODUCER, ticketId.toString(), failurePayload));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
    }

    @Test
    void shouldAckAWrongAttemptNumberAsStaleWithoutAdvancingOrDeadLettering() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-wrong-attempt", ALLOWED_PRODUCER, ticketId.toString(),
            successPayload("wf-9000", resolutionCycleId, "ver-1234", 2, Instant.parse("2026-08-08T17:55:00Z"))
        ));

        await().pollDelay(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ?", Integer.class, ticketId
            );
            assertThat(outboxCount).isZero();
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200)).isNull();
        });
    }
}
