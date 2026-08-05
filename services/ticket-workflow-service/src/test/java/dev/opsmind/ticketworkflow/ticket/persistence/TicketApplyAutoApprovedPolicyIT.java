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
 * SPEC-TW-018 persistence/E2E: full Spring context + real PostgreSQL and
 * RabbitMQ Testcontainers, publishing a real {@code
 * policy.action-auto-approved.v1} message and letting the real {@code
 * @RabbitListener} (via {@code ApprovalEventsDispatcher}) consume it.
 * Unlike {@code TicketApplyApprovalRejectedIT}/{@code
 * TicketApplyApprovalExpiredIT} (SPEC-TW-016/017), the ticket is seeded
 * directly {@code IN_PROGRESS} with no open approval request — SPEC-TW-018
 * never goes through SPEC-TW-014's request-approval flow.
 */
@Tag("integration")
class TicketApplyAutoApprovedPolicyIT extends AbstractTicketAssignmentIT {

    private static final String ALLOWED_PRODUCER = "policy-approval-service";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private UUID seedInProgressTicket(UUID supportQueueId) {
        return seedAssignedTicket(UUID.randomUUID(), DEFAULT_TEAM_ID, supportQueueId, DEFAULT_ASSIGNEE_ID, TicketAssignmentStatus.IN_PROGRESS);
    }

    private String envelope(String eventId, String producer, String ticketId, String payload) {
        return """
            {
              "eventId": "%s",
              "eventType": "policy.action_auto_approved",
              "eventVersion": "1.0",
              "occurredAt": "2026-07-23T16:55:00Z",
              "producer": "%s",
              "traceId": "trace-1",
              "correlationId": "corr-1",
              "ticketId": "%s",
              "dataClassification": "INTERNAL",
              "payload": %s
            }
            """.formatted(eventId, producer, ticketId, payload);
    }

    private String autoApprovedPayload(String workflowId, String actionId, String riskLevel, String policyDecisionId, Instant decidedAt) {
        return """
            {
              "workflowId": "%s",
              "actionId": "%s",
              "actionType": "REFRESH_USER_SESSION",
              "riskLevel": "%s",
              "policyId": "policy-42",
              "policyVersion": "1.0",
              "policyDecisionId": "%s",
              "decidedAt": "%s"
            }
            """.formatted(workflowId, actionId, riskLevel, policyDecisionId, decidedAt);
    }

    private void publish(String body) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.EVENTS_EXCHANGE, "policy.action-auto-approved.v1", body);
    }

    @Test
    void shouldApplyAutoApprovedPolicyAndKeepTheTicketInProgress() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedInProgressTicket(supportQueueId);
        String policyDecisionId = "policy-dec-" + UUID.randomUUID();

        publish(envelope(
            "evt-auto-1", ALLOWED_PRODUCER, ticketId.toString(),
            autoApprovedPayload("wf-9000", "act-100", "LOW", policyDecisionId, Instant.parse("2026-07-23T16:55:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> ticketRow = ticketRow(ticketId);
            assertThat(ticketRow.get("status")).isEqualTo("IN_PROGRESS");
            assertThat(((Number) ticketRow.get("version")).longValue()).isEqualTo(1L);
        });

        Map<String, Object> requestRow = jdbcTemplate.queryForMap(
            "SELECT * FROM ticket.ticket_approval_requests WHERE policy_decision_id = ?", policyDecisionId
        );
        assertThat(requestRow.get("request_status")).isEqualTo("AUTO_APPROVED");
        assertThat(requestRow.get("policy_id")).isEqualTo("policy-42");
        assertThat(requestRow.get("policy_version")).isEqualTo("1.0");
        assertThat(requestRow.get("authorization_reference")).isNotNull();
        assertThat(requestRow.get("auto_approval_event_id")).isEqualTo("evt-auto-1");

        Integer historyCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.ticket_status_history WHERE ticket_id = ? AND transition_id = 'SM-020'", Integer.class, ticketId
        );
        assertThat(historyCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.auto-approval-applied'", Integer.class, ticketId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldBeIdempotentOnADuplicateDelivery() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedInProgressTicket(supportQueueId);
        String policyDecisionId = "policy-dec-" + UUID.randomUUID();
        String body = envelope(
            "evt-auto-dup", ALLOWED_PRODUCER, ticketId.toString(),
            autoApprovedPayload("wf-9000", "act-100", "LOW", policyDecisionId, Instant.parse("2026-07-23T16:55:00Z"))
        );

        publish(body);
        await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.ticket_approval_requests WHERE policy_decision_id = ?", Integer.class, policyDecisionId
            );
            assertThat(count).isEqualTo(1);
        });

        publish(body);
        // Duplicate is classified DUPLICATE and ACKed without a second write; give the listener time to process it, then assert no extra side effects.
        await().pollDelay(Duration.ofSeconds(2)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Integer requestCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.ticket_approval_requests WHERE policy_decision_id = ?", Integer.class, policyDecisionId
            );
            assertThat(requestCount).isEqualTo(1);
            Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.outbox_events WHERE ticket_id = ? AND event_type = 'ticket.auto-approval-applied'", Integer.class, ticketId
            );
            assertThat(outboxCount).isEqualTo(1);
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isEqualTo(1L);
        });
    }

    @Test
    void shouldSendAWrongProducerMessageToTheDeadLetterQueueWithoutAdvancingTheTicket() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedInProgressTicket(supportQueueId);
        String policyDecisionId = "policy-dec-" + UUID.randomUUID();
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200); // drain any stale message from a prior test

        publish(envelope(
            "evt-wrong-producer", "untrusted-service", ticketId.toString(),
            autoApprovedPayload("wf-9000", "act-100", "LOW", policyDecisionId, Instant.parse("2026-07-23T16:55:00Z"))
        ));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
    }

    @Test
    void shouldSendASchemaInvalidMessageToTheDeadLetterQueue() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedInProgressTicket(supportQueueId);
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200);

        String invalidPayload = """
            {"workflowId": "wf-9000", "actionId": "act-100"}
            """;
        publish(envelope("evt-invalid-schema", ALLOWED_PRODUCER, ticketId.toString(), invalidPayload));

        await().atMost(AWAIT_TIMEOUT).untilAsserted(() ->
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNotNull()
        );
        assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
    }

    /** SPEC-TW-018 acceptance-criteria: "Missing policy match cannot silently approve." */
    @Test
    void shouldAckANonLowRiskLevelAsRejectedBusinessRuleWithoutCreatingARequest() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedInProgressTicket(supportQueueId);
        String policyDecisionId = "policy-dec-" + UUID.randomUUID();
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-business-rule", ALLOWED_PRODUCER, ticketId.toString(),
            autoApprovedPayload("wf-9000", "act-100", "HIGH", policyDecisionId, Instant.parse("2026-07-23T16:55:00Z"))
        ));

        await().pollDelay(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(((Number) ticketRow(ticketId).get("version")).longValue()).isZero();
            Integer requestCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.ticket_approval_requests WHERE policy_decision_id = ?", Integer.class, policyDecisionId
            );
            assertThat(requestCount).isZero();
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNull();
        });
    }

    @Test
    void shouldAckAsStaleWithoutAdvancingWhenTheTicketIsNotInProgress() {
        UUID supportQueueId = seedSupportQueue(DEFAULT_TEAM_ID, true);
        UUID ticketId = seedInProgressTicket(supportQueueId);
        jdbcTemplate.update("UPDATE ticket.tickets SET status = 'ESCALATED' WHERE ticket_id = ?", ticketId);
        String policyDecisionId = "policy-dec-" + UUID.randomUUID();
        rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200);

        publish(envelope(
            "evt-stale", ALLOWED_PRODUCER, ticketId.toString(),
            autoApprovedPayload("wf-9000", "act-100", "LOW", policyDecisionId, Instant.parse("2026-07-23T16:55:00Z"))
        ));

        await().pollDelay(Duration.ofSeconds(3)).atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            assertThat(ticketRow(ticketId).get("status")).isEqualTo("ESCALATED");
            Integer requestCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket.ticket_approval_requests WHERE policy_decision_id = ?", Integer.class, policyDecisionId
            );
            assertThat(requestCount).isZero();
            assertThat(rabbitTemplate.receive(RabbitMqConfiguration.APPROVAL_EVENTS_DLQ, 200)).isNull();
        });
    }
}
