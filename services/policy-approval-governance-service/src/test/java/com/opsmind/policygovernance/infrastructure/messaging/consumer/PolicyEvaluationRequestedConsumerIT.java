package com.opsmind.policygovernance.infrastructure.messaging.consumer;

import com.opsmind.policygovernance.application.PolicyEvaluationRequestedEventHandler;
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
 * SPEC-PG-028 (test-plan §Contract Tests: "policy decision shape with 04
 * Memory Knowledge"). Mirrors {@code ToolApprovalRequiredConsumerIT}
 * exactly, targeting {@code policy_decisions} instead of
 * {@code approval_requests} — see that class's own javadoc.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class PolicyEvaluationRequestedConsumerIT implements PostgresContainerSupport, RabbitMqContainerSupport {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        // approval_requests has a FK to policy_decisions, so it must be truncated in the same
        // statement even though this test never creates one directly.
        jdbcTemplate.execute("TRUNCATE TABLE governance.approval_decisions, governance.approval_requests, "
            + "governance.policy_decisions, governance.governance_audit_records, governance.outbox_events, governance.processed_events");
    }

    private void publish(String eventId, String decisionKey) {
        String body = """
            {
              "eventId": "%s",
              "eventType": "policy.evaluation.requested.v1",
              "producer": "memory-knowledge-service",
              "schemaVersion": 1,
              "aggregateId": "mem-1",
              "ticketId": "ticket-1",
              "correlationId": "corr-1",
              "causationId": "cause-0",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {
                "decisionKey": "%s",
                "inputHash": "hash-1",
                "subjectType": "user",
                "subjectId": "user-1",
                "actionType": "READ",
                "sourceRequestId": "src-req-1",
                "ticketId": "ticket-1",
                "policyId": "policy-1"
              }
            }
            """.formatted(eventId, decisionKey);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
            RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, "policy.evaluation.requested.v1",
            new Message(body.getBytes(StandardCharsets.UTF_8), properties)
        );
    }

    @Test
    void consumingAPolicyEvaluationRequestedEventCreatesAPolicyDecision() {
        publish("evt-consume-1", "dk-consume-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.policy_decisions WHERE decision_key = ?", Integer.class, "dk-consume-1"
            );
            assertThat(count).isEqualTo(1);
        });

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.processed_events WHERE event_id = ? AND consumer_name = ?",
            Integer.class, "evt-consume-1", PolicyEvaluationRequestedEventHandler.CONSUMER_NAME
        );
        assertThat(processed).isEqualTo(1);
    }

    @Test
    void redeliveringTheSameEventDoesNotCreateADuplicateDecision() {
        publish("evt-redelivered-1", "dk-redelivered-1");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.policy_decisions WHERE decision_key = ?", Integer.class, "dk-redelivered-1"
            );
            assertThat(count).isEqualTo(1);
        });

        publish("evt-redelivered-1", "dk-redelivered-1");

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.policy_decisions WHERE decision_key = ?", Integer.class, "dk-redelivered-1"
            );
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    void aMalformedMessageIsDeadLetteredNotEndlesslyRetried() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(
            RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE, "policy.evaluation.requested.v1",
            new Message("not valid json".getBytes(StandardCharsets.UTF_8), properties)
        );

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message dlqMessage = rabbitTemplate.receive(RabbitConfig.POLICY_EVALUATION_EVENTS_DLQ, 500);
            assertThat(dlqMessage).isNotNull();
        });
    }
}
