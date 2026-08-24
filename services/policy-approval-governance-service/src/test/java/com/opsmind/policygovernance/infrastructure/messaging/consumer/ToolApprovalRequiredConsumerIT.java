package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.opsmind.policygovernance.application.ToolApprovalRequiredEventHandler;
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
 * SPEC-PG-025 (test-plan §Contract Tests: "risk/approval event shape with 05
 * Tool Gateway"). Publishes a real {@code tool.approval.required.v1}
 * message to the real {@code opsmind.events} exchange and lets the real
 * {@link ToolApprovalRequiredEventConsumer} {@code @RabbitListener} consume
 * it — unlike {@code GovernanceOutboxIT}, which only exercises the
 * <em>outbound</em> publish path, this is the first integration test in
 * this service exercising an inbound consumer end to end.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class ToolApprovalRequiredConsumerIT implements PostgresContainerSupport, RabbitMqContainerSupport {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        jdbcTemplate.execute("TRUNCATE TABLE governance.approval_decisions, governance.approval_requests, "
            + "governance.governance_audit_records, governance.outbox_events, governance.processed_events");
    }

    private void publish(String eventId, String toolRequestId) {
        String body = """
            {
              "eventId": "%s",
              "eventType": "tool.approval.required.v1",
              "producer": "tool-integration-gateway-service",
              "schemaVersion": 1,
              "aggregateId": "%s",
              "ticketId": "ticket-1",
              "correlationId": "corr-1",
              "causationId": "cause-0",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {
                "toolRequestId": "%s",
                "ticketId": "ticket-1",
                "workflowInstanceId": "wf-1",
                "riskLevel": "HIGH",
                "inputHash": "hash-1",
                "constraints": []
              }
            }
            """.formatted(eventId, toolRequestId, toolRequestId);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
            RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, "tool.approval.required.v1",
            new Message(body.getBytes(StandardCharsets.UTF_8), properties)
        );
    }

    @Test
    void consumingAToolApprovalRequiredEventCreatesAnApprovalRequest() {
        publish("evt-consume-1", "tool-req-consume-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.approval_requests WHERE request_key = ?", Integer.class, "tool-req-consume-1"
            );
            assertThat(count).isEqualTo(1);
        });

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.processed_events WHERE event_id = ? AND consumer_name = ?",
            Integer.class, "evt-consume-1", ToolApprovalRequiredEventHandler.CONSUMER_NAME
        );
        assertThat(processed).isEqualTo(1);
    }

    /** 06-event-contracts §Idempotency: a redelivered message (same eventId) must not create a second ApprovalRequest. */
    @Test
    void redeliveringTheSameEventDoesNotCreateADuplicateApprovalRequest() {
        publish("evt-redelivered-1", "tool-req-redelivered-1");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.approval_requests WHERE request_key = ?", Integer.class, "tool-req-redelivered-1"
            );
            assertThat(count).isEqualTo(1);
        });

        publish("evt-redelivered-1", "tool-req-redelivered-1");

        // Give the second delivery time to be (not) processed, then assert it never duplicated.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.approval_requests WHERE request_key = ?", Integer.class, "tool-req-redelivered-1"
            );
            assertThat(count).isEqualTo(1);
        });
    }

    /** A malformed message (missing payload) is rejected to the DLQ rather than crashing the consumer for every other message. */
    @Test
    void aMalformedMessageIsDeadLetteredNotEndlesslyRetried() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
            RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, "tool.approval.required.v1",
            new Message("not valid json".getBytes(StandardCharsets.UTF_8), properties)
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message dlqMessage = rabbitTemplate.receive(RabbitConfig.TOOL_APPROVAL_EVENTS_DLQ, 500);
            assertThat(dlqMessage).isNotNull();
        });
    }
}
