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
 * SPEC-TW-021 persistence/E2E: full Spring context + real PostgreSQL and
 * RabbitMQ Testcontainers, publishing a real {@code tool.execution.result-unknown.v1}
 * message (routing key hyphenated, {@code eventType} underscored — see
 * {@code ToolResultUnknownEventConsumer}'s Javadoc) on the same {@code
 * ticket-workflow.tool-execution-events.v1} queue SPEC-TW-019/020 use.
 * Mirrors {@code TicketApplyToolExecutionCompletedIT}'s seeding style.
 */
@Tag("integration")
class TicketApplyToolResultUnknownIT extends AbstractTicketAssignmentIT {

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
              "eventType": "tool.execution.result_unknown",
              "eventVersion": "1.0",
              "occurredAt": "2026-08-06T17:55:00Z",
              "producer": "%s",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": %s
            }
            """.formatted(eventId, producer, ticketId, payload);
    }

    private String unknownPayload(String workflowId, String actionId, String actionType, String authorizationReference, String toolExecutionId, Instant observedAt) {
        return """
            {
              "workflowId": "%s",
              "actionId": "%s",
              "actionType": "%s",
              "authorizationReference": "%s",
              "toolExecutionId": "%s",
              "unknownReason": "TIMEOUT_AFTER_REQUEST_SENT",
              "evidenceReferences": ["log-ref-1", "log-ref-2"],
              "observedAt": "%s"
            }
            """.formatted(workflowId, actionId, actionType, authorizationReference, toolExecutionId, observedAt);
    }

    private void publish(String body) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, "tool.execution.result-unknown.v1", body);
    }

    @Test
    void shouldRecordAnUnknownResultAndEscalateTheTicket() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");

        Instant observedAt = Instant.parse("2026-08-06T17:55:00Z");
        publish(envelope("evt-unknown-1", ALLOWED_PRODUCER, ticketId.toString(), unknownPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", observedAt)));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> ticketRow = ticketRow(ticketId);
            assertThat(ticketRow.get("status")).isEqualTo("ESCALATED");
            assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);
        });

        Map<String, Object> resultRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_tool_execution_results WHERE tool_execution_id = ?", "exec-500"
        );
        assertThat(resultRow.get("result_status")).isEqualTo("UNKNOWN");
        assertThat(resultRow.get("unknown_reason")).isEqualTo("TIMEOUT_AFTER_REQUEST_SENT");
        assertThat(resultRow.get("reconciliation_required")).isEqualTo(true);
        assertThat(resultRow.get("authorization_reference")).isEqualTo("auth-5678");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-024'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.tool-result-unknown-recorded'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldBeIdempotentOnADuplicateDelivery() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        String body = envelope(
            "evt-unknown-dup", ALLOWED_PRODUCER, ticketId.toString(),
            unknownPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", Instant.parse("2026-08-06T17:55:00Z"))
        );

        publish(body);
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("ESCALATED")
        );

        publish(body);
        await().pollDelay(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.tool-result-unknown-recorded'", Integer.class, ticketId
            );
            assertThat(outboxCount).isEqualTo(1);
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void shouldFlagConflictRequiresReconciliationWhenACompletedResultAlreadyExists() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_tool_execution_results
                (tool_execution_id, ticket_id, workflow_id, action_id, authorization_reference,
                 result_status, tool_result_id, completed_at, event_id, recorded_at)
            VALUES ('exec-500', ?, 'wf-9000', 'act-100', 'auth-5678', 'COMPLETED', 'result-900', ?, 'evt-completed-0', ?)
            """,
            ticketId, Timestamp.from(SEED_NOW), Timestamp.from(SEED_NOW)
        );
        // The ticket itself has already moved on (SPEC-TW-019 would have advanced it to VERIFYING); force it back to EXECUTING
        // is intentionally NOT done here — the conflict path must never depend on, or touch, ticket status at all.
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'VERIFYING' WHERE ticket_id = ?", ticketId);

        publish(envelope(
            "evt-unknown-late", ALLOWED_PRODUCER, ticketId.toString(),
            unknownPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", Instant.parse("2026-08-06T17:56:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> resultRow = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket.ticket_tool_execution_results WHERE tool_execution_id = ?", "exec-500"
            );
            assertThat(resultRow.get("result_status")).isEqualTo("COMPLETED");
            assertThat(resultRow.get("reconciliation_required")).isEqualTo(true);
            assertThat(resultRow.get("conflict_event_id")).isEqualTo("evt-unknown-late");
        });

        // The ticket status is left untouched by the conflict path.
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("VERIFYING");
    }

    @Test
    void shouldSendAWrongProducerMessageToTheDeadLetterQueueWithoutAdvancingTheTicket() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID approvalRequestId = UUID.randomUUID();
        UUID ticketId = seedExecutingTicket(supportQueueId, approvalRequestId, "wf-9000", "act-100", "RESET_MFA", "auth-5678");
        rabbitTemplate.receive(RabbitMqConfiguration.TOOL_EXECUTION_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-wrong-producer", "untrusted-service", ticketId.toString(),
            unknownPayload("wf-9000", "act-100", "RESET_MFA", "auth-5678", "exec-500", Instant.parse("2026-08-06T17:55:00Z"))
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
            unknownPayload("wf-9000", "act-100", "RESET_MFA", "auth-DIFFERENT", "exec-500", Instant.parse("2026-08-06T17:55:00Z"))
        ));

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
