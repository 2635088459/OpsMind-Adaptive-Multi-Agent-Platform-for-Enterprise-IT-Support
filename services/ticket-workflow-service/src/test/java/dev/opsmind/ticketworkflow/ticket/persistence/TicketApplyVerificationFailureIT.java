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
 * SPEC-TW-024 persistence/E2E: full Spring context + real PostgreSQL and
 * RabbitMQ Testcontainers, publishing a real {@code verification.failed.v1}
 * message and letting the real {@code @RabbitListener} consume it. The
 * ticket is seeded directly in {@code VERIFYING} with an {@code ACTIVE}
 * {@code ticket_verification_attempts} row (SPEC-TW-022) — the attempt this
 * event must match. Mirrors {@code TicketApplyVerificationSuccessIT}'s
 * (SPEC-TW-023) shape.
 */
@Tag("integration")
class TicketApplyVerificationFailureIT extends AbstractTicketAssignmentIT {

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

    private void seedFailedAttempt(UUID ticketId, UUID resolutionCycleId, String verificationId, int attemptNumber) {
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_verification_attempts
                (verification_id, ticket_id, resolution_cycle_id, workflow_id, tool_result_id, attempt_number,
                 attempt_status, verification_type, started_at, failure_code, failure_class, failed_at, failed_event_id)
            VALUES (?, ?, ?, 'wf-9000', ?, ?, 'FAILED', 'IDENTITY_LOGIN_CHECK', ?, 'LOGIN_STILL_FAILS', 'RETRYABLE', ?, 'evt-prior-failure')
            """,
            verificationId, ticketId, resolutionCycleId, "tool-result-" + verificationId, attemptNumber, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW)
        );
    }

    private UUID resolutionCycleId(UUID ticketId) {
        return jdbcTemplate.queryForObject("SELECT current_resolution_cycle_id FROM ticket.tickets WHERE ticket_id = ?", UUID.class, ticketId);
    }

    private String envelope(String eventId, String producer, String ticketId, String payload) {
        return """
            {
              "eventId": "%s",
              "eventType": "verification.failed",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-09T17:55:00Z",
              "producer": "%s",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": %s
            }
            """.formatted(eventId, producer, ticketId, payload);
    }

    private String failurePayload(String workflowId, UUID resolutionCycleId, String verificationId, int attemptNumber, String failureClass, boolean unsafe, Instant failedAt) {
        return """
            {
              "workflowId": "%s",
              "resolutionCycleId": "%s",
              "verificationId": "%s",
              "attemptNumber": %d,
              "failureCode": "LOGIN_STILL_FAILS",
              "failureClass": "%s",
              "unsafe": %s,
              "failedAt": "%s"
            }
            """.formatted(workflowId, resolutionCycleId, verificationId, attemptNumber, failureClass, unsafe, failedAt);
    }

    private void publish(String body) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, "verification.failed.v1", body);
    }

    @Test
    void shouldApplyARetryableFailureAndReturnTheTicketToInProgress() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);

        Instant failedAt = Instant.parse("2026-08-09T17:55:00Z");
        publish(envelope("evt-failed-1", ALLOWED_PRODUCER, ticketId.toString(), failurePayload("wf-9000", resolutionCycleId, "ver-1234", 1, "RETRYABLE", false, failedAt)));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> ticketRow = ticketRow(ticketId);
            assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);
        });

        Map<String, Object> attemptRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_verification_attempts WHERE verification_id = ?", "ver-1234"
        );
        assertThat(attemptRow.get("attempt_status")).isEqualTo("FAILED");
        assertThat(attemptRow.get("failure_code")).isEqualTo("LOGIN_STILL_FAILS");
        assertThat(attemptRow.get("failure_class")).isEqualTo("RETRYABLE");
        assertThat(attemptRow.get("unsafe_result")).isEqualTo(false);

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-027'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.verification-failure-applied'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldEscalateOnTheThirdFailureInTheSameResolutionCycle() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-3", "wf-9000", 3);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        seedFailedAttempt(ticketId, resolutionCycleId, "ver-1", 1);
        seedFailedAttempt(ticketId, resolutionCycleId, "ver-2", 2);

        publish(envelope("evt-failed-third", ALLOWED_PRODUCER, ticketId.toString(), failurePayload("wf-9000", resolutionCycleId, "ver-3", 3, "RETRYABLE", false, Instant.parse("2026-08-09T17:56:00Z"))));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("ESCALATED")
        );

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-028'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    void shouldEscalateOnAnUnsafeResultEvenOnTheFirstFailure() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);

        publish(envelope("evt-failed-unsafe", ALLOWED_PRODUCER, ticketId.toString(), failurePayload("wf-9000", resolutionCycleId, "ver-1234", 1, "RETRYABLE", true, Instant.parse("2026-08-09T17:55:00Z"))));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("ESCALATED")
        );

        Map<String, Object> attemptRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_verification_attempts WHERE verification_id = ?", "ver-1234"
        );
        assertThat(attemptRow.get("unsafe_result")).isEqualTo(true);
    }

    @Test
    void shouldApplyAPipelineFailureAndMoveTheTicketToFailed() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);

        publish(envelope("evt-failed-pipeline", ALLOWED_PRODUCER, ticketId.toString(), failurePayload("wf-9000", resolutionCycleId, "ver-1234", 1, "PIPELINE_FAILED", false, Instant.parse("2026-08-09T17:55:00Z"))));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("FAILED")
        );

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-029'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    void shouldBeIdempotentOnADuplicateDelivery() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        String body = envelope(
            "evt-failed-dup", ALLOWED_PRODUCER, ticketId.toString(),
            failurePayload("wf-9000", resolutionCycleId, "ver-1234", 1, "RETRYABLE", false, Instant.parse("2026-08-09T17:55:00Z"))
        );

        publish(body);
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L)
        );

        publish(body);
        await().pollDelay(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.verification-failure-applied'", Integer.class, ticketId
            );
            assertThat(outboxCount).isEqualTo(1);
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void shouldFlagConflictRequiresReconciliationWhenASuccessResultAlreadyExists() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        jdbcTemplate.update("""
            UPDATE ticket.ticket_verification_attempts
            SET attempt_status = 'SUCCEEDED', verification_evidence_id = 'evidence-900', completed_at = ?, completed_event_id = 'evt-prior-success'
            WHERE verification_id = ?
            """,
            Timestamp.from(SEED_NOW), "ver-1234"
        );

        publish(envelope(
            "evt-failed-late", ALLOWED_PRODUCER, ticketId.toString(),
            failurePayload("wf-9000", resolutionCycleId, "ver-1234", 1, "RETRYABLE", false, Instant.parse("2026-08-09T17:56:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> attemptRow = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket.ticket_verification_attempts WHERE verification_id = ?", "ver-1234"
            );
            assertThat(attemptRow.get("attempt_status")).isEqualTo("CONFLICT");
            assertThat(attemptRow.get("conflict_event_id")).isEqualTo("evt-failed-late");
        });

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
            failurePayload("wf-9000", resolutionCycleId, "ver-1234", 1, "RETRYABLE", false, Instant.parse("2026-08-09T17:55:00Z"))
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
    void shouldAckAWrongAttemptNumberAsStaleWithoutAdvancingOrDeadLettering() {
        UUID ticketId = seedVerifyingTicketWithActiveAttempt("ver-1234", "wf-9000", 1);
        UUID resolutionCycleId = resolutionCycleId(ticketId);
        rabbitTemplate.receive(RabbitMqConfiguration.VERIFICATION_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-wrong-attempt", ALLOWED_PRODUCER, ticketId.toString(),
            failurePayload("wf-9000", resolutionCycleId, "ver-1234", 2, "RETRYABLE", false, Instant.parse("2026-08-09T17:55:00Z"))
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
