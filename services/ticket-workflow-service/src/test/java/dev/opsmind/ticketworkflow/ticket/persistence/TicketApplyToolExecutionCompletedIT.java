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
 * SPEC-TW-019 persistence/E2E: full Spring context + real PostgreSQL and
 * RabbitMQ Testcontainers, publishing a real {@code tool.execution.completed.v1}
 * message and letting the real {@code @RabbitListener} consume it. Mirrors
 * {@code TicketApplyApprovalGrantedIT}'s Postgres-seeding style, but seeds
 * the ticket directly in {@code EXECUTING} (no Phase 06 "start execution"
 * transition exists yet — see phase-06-tool-execution's plan doc) with the
 * {@code GRANTED} approval request that authorized it, since that row is
 * the durable source of the {@code authorizationReference} the tool result
 * must match.
 */
@Tag("integration")
class TicketApplyToolExecutionCompletedIT extends AbstractTicketAssignmentIT {

    private static final String ALLOWED_PRODUCER = "tool-gateway-service";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private UUID seedExecutingTicket(UUID supportQueueId, UUID approvalRequestId, String workflowId, String actionId, String actionType, String authorizationReference) {
        UUID ticketId = seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'EXECUTING' WHERE ticket_id = ?", ticketId);
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_approval_requests
                (approval_request_id, ticket_id, approval_id, workflow_id, action_id, action_type, request_status,
                 risk_level, risk_context, requested_by_type, requested_by_id, requested_at,
                 approved_by, approved_at, authorization_reference, granted_event_id)
            VALUES (?, ?, ?, ?, ?, ?, 'GRANTED', 'HIGH', '{"targetSystem":"identity"}'::jsonb, 'IT_SUPPORT', 'sam.support', ?,
                    'sha256:approver', ?, ?, 'evt-granted-0')
            """,
            approvalRequestId, ticketId, "appr-" + approvalRequestId, workflowId, actionId, actionType, Timestamp.from(SEED_NOW),
            Timestamp.from(SEED_NOW), authorizationReference
        );
        return ticketId;
    }

    private String envelope(String eventId, String producer, String ticketId, String payload) {
        return """
            {
              "eventId": "%s",
              "eventType": "tool.execution.completed",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-04T17:55:00Z",
              "producer": "%s",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": %s
            }
            """.formatted(eventId, producer, ticketId, payload);
    }

    private String completedPayload(String workflowId, String actionId, String actionType, String authorizationReference, String toolExecutionId, Instant completedAt) {
        return """
            {
              "workflowId": "%s",
              "actionId": "%s",
              "actionType": "%s",
              "authorizationReference": "%s",
              "toolExecutionId": "%s",
              "toolResultId": "result-900",
              "completedAt": "%s",
              "resultSummary": {"resultCode": "DUO_ENROLLMENT_RESET", "changed": true}
            }
            """.formatted(workflowId, actionId, actionType, authorizationReference, toolExecutionId, completedAt);
    }

    private void publish(String body) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, "tool.execution.completed.v1", body);
    }

    @Test
    void shouldApplyToolExecutionCompletedAndTransitionTheTicketToVerifying() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");

        Instant completedAt = Instant.parse("2026-08-04T17:55:00Z");
        publish(envelope("evt-completed-1", ALLOWED_PRODUCER, ticketId.toString(), completedPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", completedAt)));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> ticketRow = ticketRow(ticketId);
            assertThat(ticketRow.get("status")).isEqualTo("VERIFYING");
            assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);
        });

        Map<String, Object> resultRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_tool_execution_results WHERE tool_execution_id = ?", "exec-500"
        );
        assertThat(resultRow.get("ticket_id")).isEqualTo(ticketId);
        assertThat(resultRow.get("result_status")).isEqualTo("COMPLETED");
        assertThat(resultRow.get("tool_result_id")).isEqualTo("result-900");
        assertThat(resultRow.get("authorization_reference")).isEqualTo("auth-5678");
        assertThat(resultRow.get("event_id")).isEqualTo("evt-completed-1");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-021'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.tool-execution-completed-applied'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldBeIdempotentOnADuplicateDelivery() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        String body = envelope(
            "evt-completed-dup", ALLOWED_PRODUCER, ticketId.toString(),
            completedPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", Instant.parse("2026-08-04T17:55:00Z"))
        );

        publish(body);
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("VERIFYING")
        );

        publish(body);
        // Duplicate is classified DUPLICATE and ACKed without a second write; give the listener time to process it, then assert no extra side effects.
        await().pollDelay(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.tool-execution-completed-applied'", Integer.class, ticketId
            );
            assertThat(outboxCount).isEqualTo(1);
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void shouldSendAWrongProducerMessageToTheDeadLetterQueueWithoutAdvancingTheTicket() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200); // drain any stale message from a prior test

        publish(envelope(
            "evt-wrong-producer", "untrusted-service", ticketId.toString(),
            completedPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", Instant.parse("2026-08-04T17:55:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("EXECUTING");
    }

    @Test
    void shouldSendASchemaInvalidMessageToTheDeadLetterQueue() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200);

        String invalidPayload = """
            {"workflowId": "wf-9000", "actionId": "act-100"}
            """;
        publish(envelope("evt-invalid-schema", ALLOWED_PRODUCER, ticketId.toString(), invalidPayload));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("EXECUTING");
    }

    @Test
    void shouldAckAReferenceMismatchAsStaleWithoutAdvancingOrDeadLettering() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-mismatch", ALLOWED_PRODUCER, ticketId.toString(),
            completedPayload("wf-9000", "act-100", "RESET_MFA", "auth-DIFFERENT", "exec-500", Instant.parse("2026-08-04T17:55:00Z"))
        ));

        // STALE is a pure no-op ACK with no positive DB signal to await on; give the async listener time to actually process the message before asserting nothing happened.
        await().pollDelay(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("EXECUTING");
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ?", Integer.class, ticketId
            );
            assertThat(outboxCount).isZero();
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200)).isNull();
        });
    }
}
