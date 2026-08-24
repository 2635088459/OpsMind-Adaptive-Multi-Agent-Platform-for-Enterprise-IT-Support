package com.opsmind.policygovernance.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.ApprovalService;
import com.opsmind.policygovernance.application.OutboxDispatchService;
import com.opsmind.policygovernance.application.ToolApprovalRequiredEventHandler;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.config.RabbitConfig;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.support.PostgresContainerSupport;
import com.opsmind.policygovernance.support.RabbitMqContainerSupport;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SPEC-PG-035 (goal: "approval lifecycle e2e harness"). Every earlier
 * integration test in this codebase exercises exactly one step of the
 * lifecycle in isolation — {@code ToolApprovalRequiredConsumerIT} only the
 * inbound consume, {@code GovernanceOutboxIT} only the outbox
 * stage-then-publish path, {@code ApprovalServiceTest} only the in-memory
 * decide step. This is the first test in this service that chains all
 * three against real Postgres + RabbitMQ in one flow: an inbound {@code
 * tool.approval.required.v1} message creates a real {@code ApprovalRequest}
 * via the real {@code @RabbitListener}, a real {@link ApprovalService#grant}/
 * {@link ApprovalService#deny} call decides it, and {@link
 * OutboxDispatchService#publishPending} actually hands both the resulting
 * {@code approval.requested.v1} and {@code approval.granted.v1}/{@code
 * approval.denied.v1} events to the real broker — proving the whole chain
 * wires together, not just each link.
 *
 * <p>Delivery is verified through {@code outbox_events.status = 'PUBLISHED'}
 * (SPEC-PG-003's own durability guarantee: a row only flips to {@code
 * PUBLISHED} after {@code MessageBrokerPublisherPort#publish} genuinely
 * succeeds against the real broker) plus the row's own {@code payload_json}
 * — not a second, ad-hoc consumer queue: this test's own real {@code
 * @RabbitListener} containers are concurrently active on the same shared
 * connection factory, and an anonymous drain queue declared alongside them
 * proved unreliable (the broker would reclaim it mid-test). {@code
 * GovernanceOutboxIT} can safely use an ad-hoc queue because its own tests
 * never trigger inbound consumption in parallel.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class ApprovalLifecycleE2EIT implements PostgresContainerSupport, RabbitMqContainerSupport {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private OutboxDispatchService outboxDispatchService;

    @BeforeEach
    void resetSchema() {
        jdbcTemplate.execute("TRUNCATE TABLE governance.approval_decisions, governance.approval_requests, "
            + "governance.governance_audit_records, governance.outbox_events, governance.processed_events");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * {@code ApprovalService#grant}/{@code deny} authorize against {@link
     * SecurityContextHolder}, not a parameter — mirrors {@code
     * JwtIdentityAuthorizationAdapterTest}'s own fixture, since this test
     * calls the real service bean directly (no HTTP layer runs under
     * {@code webEnvironment = NONE}) but must still pass the real RBAC/ABAC
     * check {@code JwtIdentityAuthorizationAdapter} performs.
     */
    private void authenticateAsJwt(String subject, RiskLevel riskClearance) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.put("scope", "approval:decide");
        claims.put("risk_clearance", List.of(riskClearance.name()));
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claims(c -> c.putAll(claims))
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_approval:decide")), subject)
        );
    }

    private void publishToolApprovalRequired(String eventId, String toolRequestId) {
        String body = """
            {
              "eventId": "%s",
              "eventType": "tool.approval.required.v1",
              "producer": "tool-integration-gateway-service",
              "schemaVersion": 1,
              "aggregateId": "%s",
              "ticketId": "ticket-1",
              "correlationId": "corr-e2e",
              "causationId": "cause-0",
              "occurredAt": "2026-08-23T00:00:00Z",
              "payload": {
                "toolRequestId": "%s",
                "ticketId": "ticket-1",
                "workflowInstanceId": "wf-1",
                "riskLevel": "HIGH",
                "inputHash": "hash-e2e",
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

    /**
     * Polls a {@code COUNT(*)} first, matching {@code
     * ToolApprovalRequiredConsumerIT}'s own established idiom — {@code
     * queryForObject} selecting a column directly throws {@code
     * EmptyResultDataAccessException} (not an {@code AssertionError}) while
     * no row exists yet, which Awaitility's default {@code untilAsserted}
     * does not retry on, only propagates.
     */
    private String awaitApprovalRequestId(String requestKey) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM governance.approval_requests WHERE request_key = ?", Integer.class, requestKey
            );
            assertThat(count).isEqualTo(1);
        });
        return jdbcTemplate.queryForObject(
            "SELECT approval_request_id FROM governance.approval_requests WHERE request_key = ?", String.class, requestKey
        );
    }

    /** The durability guarantee SPEC-PG-003 itself defines: a row only reaches {@code PUBLISHED} after a genuine, successful broker publish. */
    private String publishedPayload(String eventType, String approvalRequestId) {
        return jdbcTemplate.queryForObject(
            "SELECT payload_json FROM governance.outbox_events WHERE event_type = ? AND aggregate_id = ? AND status = 'PUBLISHED'",
            String.class, eventType, approvalRequestId
        );
    }

    @Test
    void fullApprovalLifecycleFromInboundEventThroughGrantToOutboundEvents() throws Exception {
        // Step 1: inbound event -> real ApprovalRequest, via the real @RabbitListener.
        publishToolApprovalRequired("evt-e2e-grant", "tool-req-e2e-grant");
        String approvalRequestId = awaitApprovalRequestId("tool-req-e2e-grant");

        Integer processed = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.processed_events WHERE event_id = ? AND consumer_name = ?",
            Integer.class, "evt-e2e-grant", ToolApprovalRequiredEventHandler.CONSUMER_NAME
        );
        assertThat(processed).isEqualTo(1);

        // Step 2: a real actor decides it — separation of duties: decidedBy differs from requestedBy (envelope.producer()).
        authenticateAsJwt("approver-1", RiskLevel.HIGH);
        ApprovalRequest granted = approvalService.grant(new DecideApprovalCommand(
            approvalRequestId, "tool-req-e2e-grant", "hash-e2e", "approver-1", "looks fine",
            List.of(), "corr-e2e-grant", "cik-e2e-grant", null, null, false, null
        ));
        assertThat(granted.status().name()).isEqualTo("APPROVED");

        // Step 3: audit trail carries both facts.
        Integer requestedAudit = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.governance_audit_records WHERE action = 'APPROVAL_REQUESTED' AND approval_request_id = ?",
            Integer.class, approvalRequestId
        );
        Integer grantedAudit = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.governance_audit_records WHERE action = 'APPROVAL_GRANTED' AND approval_request_id = ?",
            Integer.class, approvalRequestId
        );
        assertThat(requestedAudit).isEqualTo(1);
        assertThat(grantedAudit).isEqualTo(1);

        // Step 4: replay the outbox (never auto-published — 08-transaction-and-outbox's own "admin endpoint or external scheduler" seam) and confirm both events genuinely reached the real broker.
        OutboxDispatchService.DrainResult result = outboxDispatchService.publishPending();
        assertThat(result.published()).isGreaterThanOrEqualTo(2);
        assertThat(result.deadLettered()).isZero();

        String requestedPayload = publishedPayload("approval.requested.v1", approvalRequestId);
        String grantedPayload = publishedPayload("approval.granted.v1", approvalRequestId);
        assertThat(requestedPayload).as("approval.requested.v1 must actually reach the broker").isNotNull();
        assertThat(grantedPayload).as("approval.granted.v1 must actually reach the broker").isNotNull();

        JsonNode grantedEnvelope = new ObjectMapper().readTree(grantedPayload);
        assertThat(grantedEnvelope.get("payload").get("approvalRequestId").asText()).isEqualTo(approvalRequestId);
        assertThat(grantedEnvelope.get("payload").get("decidedBy").asText()).isEqualTo("approver-1");
    }

    /** INV-PG-007: denied must remain a distinguishable terminal fact, all the way out through the real broker, not just in memory. */
    @Test
    void fullApprovalLifecycleFromInboundEventThroughDenyToOutboundEvent() {
        publishToolApprovalRequired("evt-e2e-deny", "tool-req-e2e-deny");
        String approvalRequestId = awaitApprovalRequestId("tool-req-e2e-deny");

        authenticateAsJwt("approver-1", RiskLevel.HIGH);
        ApprovalRequest denied = approvalService.deny(new DecideApprovalCommand(
            approvalRequestId, "tool-req-e2e-deny", "hash-e2e", "approver-1", "too risky",
            List.of(), "corr-e2e-deny", "cik-e2e-deny", null, null, false, null
        ));
        assertThat(denied.status().name()).isEqualTo("DENIED");

        outboxDispatchService.publishPending();
        String deniedPayload = publishedPayload("approval.denied.v1", approvalRequestId);
        assertThat(deniedPayload).as("approval.denied.v1 must actually reach the broker").isNotNull();

        Integer deniedAudit = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.governance_audit_records WHERE action = 'APPROVAL_DENIED' AND approval_request_id = ?",
            Integer.class, approvalRequestId
        );
        assertThat(deniedAudit).isEqualTo(1);
    }
}
