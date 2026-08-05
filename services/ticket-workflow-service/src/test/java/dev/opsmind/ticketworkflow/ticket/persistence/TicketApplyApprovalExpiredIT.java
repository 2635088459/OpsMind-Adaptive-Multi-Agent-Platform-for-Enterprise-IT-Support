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
 * SPEC-TW-017 persistence/E2E: full Spring context + real PostgreSQL and
 * RabbitMQ Testcontainers, publishing a real {@code approval.expired.v1}
 * message and letting the real {@code @RabbitListener} (via {@code
 * ApprovalEventsDispatcher}) consume it. Mirrors {@code
 * TicketApplyApprovalRejectedIT}'s structure (SPEC-TW-016).
 */
@Tag("integration")
class TicketApplyApprovalExpiredIT extends AbstractTicketAssignmentIT {

    private static final String ALLOWED_PRODUCER = "policy-approval-service";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private UUID seedWaitingForApprovalTicket(UUID supportQueueId, String approvalId, UUID approvalRequestId, String workflowId, String actionId, String actionType) {
        return seedWaitingForApprovalTicket(supportQueueId, approvalId, approvalRequestId, workflowId, actionId, actionType, "OPEN", null);
    }

    private UUID seedWaitingForApprovalTicket(
        UUID supportQueueId, String approvalId, UUID approvalRequestId, String workflowId, String actionId, String actionType,
        String requestStatus, Instant expiresAt
    ) {
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update(
            "UPDATE ticket.tickets SET status = 'WAITING_FOR_APPROVAL', approval_reference = ? WHERE ticket_id = ?",
            approvalId, ticketId
        );
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_approval_requests
                (approval_request_id, ticket_id, approval_id, workflow_id, action_id, action_type, request_status,
                 risk_level, risk_context, requested_by_type, requested_by_id, requested_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'HIGH', '{"targetSystem":"identity"}'::jsonb, 'IT_SUPPORT', 'sam.support', ?, ?)
            """,
            approvalRequestId, ticketId, approvalId, workflowId, actionId, actionType, requestStatus,
            Timestamp.from(SEED_NOW), expiresAt == null ? null : Timestamp.from(expiresAt)
        );
        return ticketId;
    }

    private String envelope(String eventId, String producer, String ticketId, String payload) {
        return """
            {
              "eventId": "%s",
              "eventType": "approval.expired",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-03T17:50:00Z",
              "producer": "%s",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": %s
            }
            """.formatted(eventId, producer, ticketId, payload);
    }

    private String expiredPayload(String workflowId, String actionId, String approvalId, Instant expiredAt) {
        return """
            {
              "workflowId": "%s",
              "actionId": "%s",
              "approvalId": "%s",
              "expiredAt": "%s"
            }
            """.formatted(workflowId, actionId, approvalId, expiredAt);
    }

    private void publish(String body) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, "approval.expired.v1", body);
    }

    @Test
    void shouldApplyApprovalExpiredAndTransitionTheTicketBackToInProgress() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        String approvalId = "appr-" + UUID.randomUUID();
        UUID ticketId = seedWaitingForApprovalTicket(supportQueueId, approvalId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA");

        Instant expiredAt = Instant.parse("2026-07-23T18:00:00Z");
        publish(envelope("evt-expired-1", ALLOWED_PRODUCER, ticketId.toString(), expiredPayload("wf-9000", "act-100", approvalId, expiredAt)));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> ticketRow = ticketRow(ticketId);
            assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(ticketRow.get("approval_reference")).isNull();
            assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);
        });

        Map<String, Object> requestRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_approval_requests WHERE approval_request_id = ?", approvalRequestId
        );
        assertThat(requestRow.get("request_status")).isEqualTo("EXPIRED");
        assertThat(requestRow.get("expiration_reason")).isEqualTo("APPROVAL_SERVICE_TIMEOUT");
        assertThat(requestRow.get("expired_event_id")).isEqualTo("evt-expired-1");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-019'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.approval-expired-applied'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldBeIdempotentOnADuplicateDelivery() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        String approvalId = "appr-" + UUID.randomUUID();
        UUID ticketId = seedWaitingForApprovalTicket(supportQueueId, approvalId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA");
        String body = envelope(
            "evt-expired-dup", ALLOWED_PRODUCER, ticketId.toString(),
            expiredPayload("wf-9000", "act-100", approvalId, Instant.parse("2026-07-23T18:00:00Z"))
        );

        publish(body);
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS")
        );

        publish(body);
        // Duplicate is classified DUPLICATE and ACKed without a second write; give the listener time to process it, then assert no extra side effects.
        await().pollDelay(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.approval-expired-applied'", Integer.class, ticketId
            );
            assertThat(outboxCount).isEqualTo(1);
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void shouldSendAWrongProducerMessageToTheDeadLetterQueueWithoutAdvancingTheTicket() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        String approvalId = "appr-" + UUID.randomUUID();
        UUID ticketId = seedWaitingForApprovalTicket(supportQueueId, approvalId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA");
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200); // drain any stale message from a prior test

        publish(envelope(
            "evt-wrong-producer", "untrusted-service", ticketId.toString(),
            expiredPayload("wf-9000", "act-100", approvalId, Instant.parse("2026-07-23T18:00:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("WAITING_FOR_APPROVAL");
    }

    @Test
    void shouldSendASchemaInvalidMessageToTheDeadLetterQueue() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        String approvalId = "appr-" + UUID.randomUUID();
        UUID ticketId = seedWaitingForApprovalTicket(supportQueueId, approvalId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA");
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200);

        String invalidPayload = """
            {"workflowId": "wf-9000", "actionId": "act-100"}
            """;
        publish(envelope("evt-invalid-schema", ALLOWED_PRODUCER, ticketId.toString(), invalidPayload));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("WAITING_FOR_APPROVAL");
    }

    /** SPEC-TW-017 acceptance-criteria: "Granted vs expired race is decided by committed terminal state." */
    @Test
    void shouldAckAsStaleWithoutAdvancingWhenTheApprovalWasAlreadyGranted() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        String approvalId = "appr-" + UUID.randomUUID();
        UUID ticketId = seedWaitingForApprovalTicket(supportQueueId, approvalId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA");
        // Simulate SPEC-TW-015 having already committed the grant: request terminal (satisfying ck_approval_request_granted), ticket already resumed IN_PROGRESS.
        jdbcTemplate.update("""
            UPDATE ticket.ticket_approval_requests
            SET request_status = 'GRANTED', approved_by = 'sha256:approver', approved_at = ?,
                authorization_reference = 'auth-race', granted_event_id = 'evt-granted-race-source'
            WHERE approval_request_id = ?
            """, Timestamp.from(SEED_NOW), approvalRequestId);
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'IN_PROGRESS', approval_reference = NULL WHERE ticket_id = ?", ticketId);
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-granted-race", ALLOWED_PRODUCER, ticketId.toString(),
            expiredPayload("wf-9000", "act-100", approvalId, Instant.parse("2026-07-23T18:00:00Z"))
        ));

        // STALE is a pure no-op ACK with no positive DB signal to await on; give the async listener time to actually process the message before asserting nothing happened.
        await().pollDelay(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> requestRow = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket.ticket_approval_requests WHERE approval_request_id = ?", approvalRequestId
            );
            assertThat(requestRow.get("request_status")).isEqualTo("GRANTED");
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("IN_PROGRESS");
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNull();
        });
    }

    /** SPEC-TW-017 acceptance-criteria: "{@code expiredAt >= expiresAt}" business rule against the request's own stored expiry. */
    @Test
    void shouldAckABusinessRuleViolationAsRejectedBusinessRuleLeavingTheRequestOpen() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        String approvalId = "appr-" + UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-07-23T20:00:00Z");
        UUID ticketId = seedWaitingForApprovalTicket(
            supportQueueId, approvalId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "OPEN", expiresAt
        );
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200);

        // Claimed expiredAt predates the request's own stored expiresAt.
        publish(envelope(
            "evt-business-rule", ALLOWED_PRODUCER, ticketId.toString(),
            expiredPayload("wf-9000", "act-100", approvalId, Instant.parse("2026-07-23T19:00:00Z"))
        ));

        await().pollDelay(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("WAITING_FOR_APPROVAL");
            Map<String, Object> requestRow = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket.ticket_approval_requests WHERE approval_request_id = ?", approvalRequestId
            );
            assertThat(requestRow.get("request_status")).isEqualTo("OPEN");
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNull();
        });
    }
}
