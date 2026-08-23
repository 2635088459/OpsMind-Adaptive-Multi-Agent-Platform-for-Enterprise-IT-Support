package com.opsmind.policygovernance.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.OutboxDispatchService;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalGrantedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent;
import com.opsmind.policygovernance.support.PostgresContainerSupport;
import com.opsmind.policygovernance.support.RabbitMqContainerSupport;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-PG-003 (08-transaction-and-outbox, test-plan §Integration Tests
 * "audit + outbox in one transaction"). Exercises the real transactional
 * outbox write path and the real RabbitMQ publish path together against
 * Testcontainers-backed Postgres and RabbitMQ.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class GovernanceOutboxIT implements PostgresContainerSupport, RabbitMqContainerSupport {

    @Autowired
    private OutboxDispatchService outboxDispatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void resetOutbox() {
        jdbcTemplate.execute("TRUNCATE TABLE governance.outbox_events");
    }

    @Test
    void stagingAnEventDurablyInsertsAPendingOutboxRow() {
        outboxDispatchService.stage(SimpleGovernanceEvent.of("policy.decision.created.v1", "corr-1", null));

        Integer pending = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.outbox_events WHERE status = 'PENDING'", Integer.class
        );
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void publishPendingDeliversTheMessageToRabbitAndMarksTheRowPublished() {
        Queue tempQueue = amqpAdmin.declareQueue();
        Binding binding = BindingBuilder.bind(tempQueue)
            .to(new TopicExchange(RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE))
            .with("#");
        amqpAdmin.declareBinding(binding);

        outboxDispatchService.stage(SimpleGovernanceEvent.of("approval.granted.v1", "corr-99", "cause-1"));
        OutboxDispatchService.DrainResult result = outboxDispatchService.publishPending();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.retried()).isZero();
        assertThat(result.deadLettered()).isZero();

        Message message = rabbitTemplate.receive(tempQueue.getName(), 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getHeaders().get("eventType")).isEqualTo("approval.granted.v1");
        assertThat(message.getMessageProperties().getHeaders().get("correlationId")).isEqualTo("corr-99");
        assertThat(message.getMessageProperties().getHeaders().get("causationId")).isEqualTo("cause-1");

        Integer published = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.outbox_events WHERE status = 'PUBLISHED'", Integer.class
        );
        assertThat(published).isEqualTo(1);
    }

    /**
     * SPEC-PG-010: the real {@code approval.requested.v1} event — unlike
     * the {@link SimpleGovernanceEvent} placeholder the other two tests
     * here use — must carry the ApprovalRequest's own {@code
     * aggregateType}/{@code aggregateId} through to both the {@code
     * outbox_events} row and the AMQP headers, and its published body must
     * be the full 06-event-contracts envelope (producer/schemaVersion/
     * payload), not the minimal placeholder shape.
     */
    @Test
    void approvalRequestedEventPublishesWithTheRealAggregateIdentityAndFullEnvelope() throws Exception {
        Queue tempQueue = amqpAdmin.declareQueue();
        Binding binding = BindingBuilder.bind(tempQueue)
            .to(new TopicExchange(RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE))
            .with("#");
        amqpAdmin.declareBinding(binding);

        ApprovalRequest request = ApprovalRequest.requested(
            "ar-envelope", "rk-envelope", "hash-1", "tool-gateway", "src-req-envelope", "ticket-1", null,
            "tool-req-1", null, "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        outboxDispatchService.stage(ApprovalRequestedEvent.from(request, "corr-envelope", "cause-envelope"));

        Integer aggregateColumns = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.outbox_events WHERE aggregate_type = 'ApprovalRequest' AND aggregate_id = ?",
            Integer.class, "ar-envelope"
        );
        assertThat(aggregateColumns).isEqualTo(1);

        OutboxDispatchService.DrainResult result = outboxDispatchService.publishPending();
        assertThat(result.published()).isEqualTo(1);

        Message message = rabbitTemplate.receive(tempQueue.getName(), 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getHeaders().get("eventType")).isEqualTo("approval.requested.v1");
        assertThat(message.getMessageProperties().getHeaders().get("aggregateType")).isEqualTo("ApprovalRequest");
        assertThat(message.getMessageProperties().getHeaders().get("aggregateId")).isEqualTo("ar-envelope");

        JsonNode envelope = new ObjectMapper().readTree(message.getBody());
        assertThat(envelope.get("producer").asText()).isEqualTo("policy-approval-governance-service");
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("aggregateId").asText()).isEqualTo("ar-envelope");
        assertThat(envelope.get("ticketId").asText()).isEqualTo("ticket-1");
        assertThat(envelope.get("payload").get("approvalRequestId").asText()).isEqualTo("ar-envelope");
        assertThat(envelope.get("payload").get("policyDecisionId").asText()).isEqualTo("pd-1");
    }

    /**
     * SPEC-PG-013: the real {@code approval.granted.v1} event round-trips
     * through the real broker the same way SPEC-PG-010's {@code
     * approval.requested.v1} does above — including its {@code conditions}
     * list, a nested structure none of the earlier events carry, so this is
     * the first proof that shape survives real JSON serialization over AMQP.
     */
    @Test
    void approvalGrantedEventPublishesWithTheRealAggregateIdentityAndFullEnvelope() throws Exception {
        Queue tempQueue = amqpAdmin.declareQueue();
        Binding binding = BindingBuilder.bind(tempQueue)
            .to(new TopicExchange(RabbitConfig.GOVERNANCE_EVENTS_EXCHANGE))
            .with("#");
        amqpAdmin.declareBinding(binding);

        ApprovalRequest requested = ApprovalRequest.requested(
            "ar-granted-envelope", "rk-granted-envelope", "hash-1", "tool-gateway", "src-req-granted-envelope",
            "ticket-1", null, "tool-req-1", null, "pd-1", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH,
            List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );
        ApprovalDecision decision = new ApprovalDecision(
            "ad-envelope", requested.approvalRequestId(), ApprovalDecision.Outcome.APPROVED, "approver-1", Instant.now(),
            "looks fine", List.of(new Constraint(Constraint.Type.TIME_WINDOW, "business-hours")), true, "cik-1"
        );
        ApprovalRequest granted = requested.approve(decision, Instant.now());
        outboxDispatchService.stage(ApprovalGrantedEvent.from(granted, decision, "corr-granted-envelope", "cause-granted-envelope"));

        OutboxDispatchService.DrainResult result = outboxDispatchService.publishPending();
        assertThat(result.published()).isEqualTo(1);

        Message message = rabbitTemplate.receive(tempQueue.getName(), 5000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getHeaders().get("eventType")).isEqualTo("approval.granted.v1");
        assertThat(message.getMessageProperties().getHeaders().get("aggregateType")).isEqualTo("ApprovalRequest");
        assertThat(message.getMessageProperties().getHeaders().get("aggregateId")).isEqualTo("ar-granted-envelope");

        JsonNode envelope = new ObjectMapper().readTree(message.getBody());
        assertThat(envelope.get("payload").get("approvalRequestId").asText()).isEqualTo("ar-granted-envelope");
        assertThat(envelope.get("payload").get("decidedBy").asText()).isEqualTo("approver-1");
        assertThat(envelope.get("payload").get("separationOfDutiesCheck").asBoolean()).isTrue();
        assertThat(envelope.get("payload").get("conditions").get(0).get("type").asText()).isEqualTo("TIME_WINDOW");
        assertThat(envelope.get("payload").get("conditions").get(0).get("detail").asText()).isEqualTo("business-hours");
    }
}
