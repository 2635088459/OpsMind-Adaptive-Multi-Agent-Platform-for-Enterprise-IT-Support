package com.opsmind.identity.infrastructure.messaging.consumer;

import com.opsmind.identity.application.service.ApprovalDecisionEventHandler;
import com.opsmind.identity.config.RabbitConfig;
import com.opsmind.identity.support.PostgresContainerSupport;
import com.opsmind.identity.support.RabbitMqContainerSupport;
import com.opsmind.identity.support.TestSecurityConfig;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SPEC-UA-028 (06-event-contracts §Consumed events: "Domain 06: approval or
 * break-glass approved/denied/expired facts"). Publishes a real {@code
 * approval.denied.v1}/{@code approval.granted.v1} message to the real
 * {@code opsmind.events} exchange and lets the real {@link
 * ApprovalDecisionEventConsumer} {@code @RabbitListener} consume it — this
 * domain's first integration test exercising an inbound consumer end to
 * end (mirrors policy-approval-governance-service's own {@code
 * ToolApprovalRequiredConsumerIT}).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class ApprovalDecisionConsumerIT implements PostgresContainerSupport, RabbitMqContainerSupport {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        jdbcTemplate.execute("TRUNCATE TABLE identity.break_glass_grants, identity.identity_audit_records, "
            + "identity.outbox_events, identity.processed_events CASCADE");
    }

    private void publish(String routingKey, String eventId, String approvalRequestId) {
        String body = """
            {
              "eventId": "%s",
              "eventType": "%s",
              "producer": "policy-approval-governance-service",
              "schemaVersion": 1,
              "aggregateId": "%s",
              "correlationId": "corr-1",
              "causationId": "cause-0",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {
                "approvalRequestId": "%s",
                "decidedBy": "approver-1",
                "reason": "risk accepted"
              }
            }
            """.formatted(eventId, routingKey, approvalRequestId, approvalRequestId);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(RabbitConfig.IDENTITY_EVENTS_EXCHANGE, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    /**
     * {@code break_glass_grants} carries no foreign key to {@code
     * user_identities} (V013's own from-scratch schema) — a standalone
     * insert is enough. {@code java.sql.Timestamp} rather than {@code
     * Instant} directly — plain JDBC's own {@code setObject} cannot infer
     * a SQL type for {@code Instant} without an explicit {@code Types}
     * value.
     */
    private void insertActiveBreakGlassGrant(String breakGlassGrantId, String approvalReference) {
        Timestamp now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp expiresAt = Timestamp.from(now.toInstant().plusSeconds(3600));
        jdbcTemplate.update(
            "INSERT INTO identity.break_glass_grants (break_glass_grant_id, tenant_id, issuer, subject, scope_type, scope_id, "
                + "approval_reference, reason, granted_by, status, granted_at, expires_at, correlation_id, created_at, updated_at, version) "
                + "VALUES (?, 'opsmind', 'https://idp.example', ?, 'TENANT', NULL, ?, 'prod incident', ?, 'ACTIVE', ?, ?, 'corr-setup', ?, ?, 0)",
            breakGlassGrantId, breakGlassGrantId + "-subject", approvalReference, breakGlassGrantId + "-subject",
            now, expiresAt, now, now
        );
    }

    /** Consuming a real approval.denied.v1 revokes the still-ACTIVE grant that referenced this approval. */
    @Test
    void consumingAnApprovalDeniedEventRevokesTheReferencingActiveGrant() {
        insertActiveBreakGlassGrant("bg-consume-1", "approval-ref-consume-1");

        publish("approval.denied.v1", "evt-consume-1", "approval-ref-consume-1");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                "SELECT status FROM identity.break_glass_grants WHERE break_glass_grant_id = ?", String.class, "bg-consume-1"
            );
            assertThat(status).isEqualTo("REVOKED");
        });

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM identity.processed_events WHERE event_id = ? AND consumer_name = ?",
            Integer.class, "evt-consume-1", ApprovalDecisionEventHandler.CONSUMER_NAME
        );
        assertThat(processed).isEqualTo(1);

        // SPEC-UA-029: the reconciliation-triggered revoke also publishes a real identity.security.alert.v1 to the real outbox table.
        // payload_json::text re-serializes via Postgres's own JSONB formatting (spaces after colons), unlike Jackson's compact form.
        String alertPayload = jdbcTemplate.queryForObject(
            "SELECT payload_json::text FROM identity.outbox_events WHERE aggregate_id = ? AND event_type = 'identity.security.alert.v1'",
            String.class, "bg-consume-1"
        );
        assertThat(alertPayload).contains("BREAK_GLASS_REVOKED_AFTER_APPROVAL_OUTCOME").contains("\"reasonCode\": \"DENIED\"");
    }

    /** 06-event-contracts §Idempotency: a redelivered message (same eventId) must not re-process (and must not error on an already-revoked grant). */
    @Test
    void redeliveringTheSameEventDoesNotReprocess() {
        insertActiveBreakGlassGrant("bg-redelivered-1", "approval-ref-redelivered-1");
        publish("approval.denied.v1", "evt-redelivered-1", "approval-ref-redelivered-1");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                "SELECT status FROM identity.break_glass_grants WHERE break_glass_grant_id = ?", String.class, "bg-redelivered-1"
            );
            assertThat(status).isEqualTo("REVOKED");
        });

        publish("approval.denied.v1", "evt-redelivered-1", "approval-ref-redelivered-1");

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer processed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity.processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, "evt-redelivered-1", ApprovalDecisionEventHandler.CONSUMER_NAME
            );
            assertThat(processed).isEqualTo(1);
        });
    }

    /** A grant unrelated to the denied approval is left untouched. */
    @Test
    void anUnrelatedGrantIsNeverAffected() {
        insertActiveBreakGlassGrant("bg-unrelated-1", "approval-ref-unrelated-1");

        publish("approval.denied.v1", "evt-unrelated-1", "approval-ref-does-not-match-anything");

        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer processed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity.processed_events WHERE event_id = ?", Integer.class, "evt-unrelated-1"
            );
            assertThat(processed).isEqualTo(1);
        });
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM identity.break_glass_grants WHERE break_glass_grant_id = ?", String.class, "bg-unrelated-1"
        );
        assertThat(status).isEqualTo("ACTIVE");
    }

    /** A malformed message is dead-lettered rather than endlessly retried. */
    @Test
    void aMalformedMessageIsDeadLetteredNotEndlesslyRetried() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(RabbitConfig.IDENTITY_EVENTS_EXCHANGE, "approval.denied.v1", new Message("not valid json".getBytes(StandardCharsets.UTF_8), properties));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Message dlqMessage = rabbitTemplate.receive(RabbitConfig.APPROVAL_DENIED_EVENTS_DLQ, 500);
            assertThat(dlqMessage).isNotNull();
        });
    }
}
