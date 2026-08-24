package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.opsmind.policygovernance.application.TicketApprovalRequiredEventHandler;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.support.PostgresContainerSupport;
import com.opsmind.policygovernance.support.RabbitMqContainerSupport;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SPEC-PG-027 (test-plan §Contract Tests: "ticket approval shape with 02
 * Ticket Workflow"). Mirrors {@code ToolApprovalRequiredConsumerIT} exactly
 * — see that class's own javadoc.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class TicketApprovalRequiredConsumerIT implements PostgresContainerSupport, RabbitMqContainerSupport {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        jdbcTemplate.execute("TRUNCATE TABLE governance.approval_decisions, governance.approval_requests, "
            + "governance.governance_audit_records, governance.outbox_events, governance.processed_events");
    }

    private void publish(String eventId, String ticketId, String exceptionType) {
        String exceptionTypeJson = exceptionType == null ? "null" : "\"" + exceptionType + "\"";
        String body = """
            {
              "eventId": "%s",
              "eventType": "ticket.approval.required.v1",
              "producer": "ticket-workflow-service",
              "schemaVersion": 1,
              "aggregateId": "%s",
              "ticketId": "ticket-1",
              "correlationId": "corr-1",
              "causationId": "cause-0",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {
                "ticketId": "%s",
                "exceptionType": %s,
                "riskLevel": "HIGH",
                "inputHash": "hash-1",
                "constraints": []
              }
            }
            """.formatted(eventId, ticketId, ticketId, exceptionTypeJson);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
            RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, "ticket.approval.required.v1",
            new Message(body.getBytes(StandardCharsets.UTF_8), properties)
        );
    }

    @Test
    void consumingATicketApprovalRequiredEventCreatesAnApprovalRequest() {
        publish("evt-consume-1", "ticket-consume-1", "SLA_EXCEPTION");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String approvalType = jdbcTemplate.queryForObject(
                "SELECT approval_type FROM governance.approval_requests WHERE request_key = ?", String.class, "ticket-consume-1"
            );
            assertThat(approvalType).isEqualTo("TICKET_SLA_EXCEPTION");
        });

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.processed_events WHERE event_id = ? AND consumer_name = ?",
            Integer.class, "evt-consume-1", TicketApprovalRequiredEventHandler.CONSUMER_NAME
        );
        assertThat(processed).isEqualTo(1);
    }

    /** A null exceptionType is an ordinary ticket action, not an error — it must still create a request. */
    @Test
    void aNullExceptionTypeCreatesAGenericTicketActionRequest() {
        publish("evt-generic-1", "ticket-generic-1", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String approvalType = jdbcTemplate.queryForObject(
                "SELECT approval_type FROM governance.approval_requests WHERE request_key = ?", String.class, "ticket-generic-1"
            );
            assertThat(approvalType).isEqualTo("TICKET_ACTION");
        });
    }

    @Test
    void redeliveringTheSameEventDoesNotCreateADuplicateApprovalRequest() {
        publish("evt-redelivered-1", "ticket-redelivered-1", "CLOSURE_OVERRIDE");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.approval_requests WHERE request_key = ?", Integer.class, "ticket-redelivered-1"
            );
            assertThat(count).isEqualTo(1);
        });

        publish("evt-redelivered-1", "ticket-redelivered-1", "CLOSURE_OVERRIDE");

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.approval_requests WHERE request_key = ?", Integer.class, "ticket-redelivered-1"
            );
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    void aMalformedMessageIsDeadLetteredNotEndlesslyRetried() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
            RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, "ticket.approval.required.v1",
            new Message("not valid json".getBytes(StandardCharsets.UTF_8), properties)
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message dlqMessage = rabbitTemplate.receive(RabbitConfig.TICKET_APPROVAL_EVENTS_DLQ, 500);
            assertThat(dlqMessage).isNotNull();
        });
    }
}
