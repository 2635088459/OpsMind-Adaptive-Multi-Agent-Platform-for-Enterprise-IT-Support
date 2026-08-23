package com.opsmind.policygovernance.infrastructure.persistence;

import com.opsmind.policygovernance.application.port.ApprovalDecisionRepository;
import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.application.port.PolicyDecisionRepository;
import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.support.PostgresContainerSupport;
import com.opsmind.policygovernance.support.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-PG-002 (test-plan §Integration Tests: "PostgreSQL persistence and
 * unique keys", "concurrent approval grant/deny"). Exercises the real JPA
 * adapters against a Testcontainers PostgreSQL instance running the actual
 * Flyway migrations — not the in-memory test doubles the application-layer
 * unit tests use.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class GovernancePersistenceIT implements PostgresContainerSupport {

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private PolicyVersionRepository policyVersionRepository;

    @Autowired
    private PolicyDecisionRepository policyDecisionRepository;

    @Autowired
    private ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    private ApprovalDecisionRepository approvalDecisionRepository;

    @Autowired
    private GovernanceAuditRepository governanceAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        jdbcTemplate.execute("TRUNCATE TABLE governance.approval_decisions, governance.approval_requests, "
            + "governance.policy_decisions, governance.policy_versions, governance.policies, "
            + "governance.governance_audit_records, governance.outbox_events, governance.processed_events");
    }

    @Test
    void policyAndPolicyVersionRoundTripThroughPostgres() {
        Policy policy = Policy.created("policy-1", "Test Policy", "global", "author-1", Instant.now());
        policyRepository.save(policy);

        Policy loaded = policyRepository.findById("policy-1").orElseThrow();
        assertThat(loaded.policyName()).isEqualTo("Test Policy");
        assertThat(loaded.status()).isEqualTo(com.opsmind.policygovernance.domain.policy.PolicyLifecycleStatus.ACTIVE);

        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());
        PolicyVersion published = PolicyVersion.draft("pv-1", "policy-1", 1, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now().minusSeconds(10), Instant.now());
        policyVersionRepository.save(published);

        PolicyVersion effective = policyVersionRepository.findEffectiveVersion("policy-1", Instant.now()).orElseThrow();
        assertThat(effective.status()).isEqualTo(PolicyStatus.PUBLISHED);
        assertThat(effective.rules()).hasSize(1);
        assertThat(effective.reviewedBy()).isEqualTo("reviewer-1");
        assertThat(effective.publishedBy()).isEqualTo("publisher-1");
    }

    @Test
    void policyDecisionEnforcesUniqueDecisionKeyAndInputHash() {
        PolicyDecision decision = policyDecision("dk-1", "hash-1");
        policyDecisionRepository.save(decision);

        assertThatThrownBy(() -> policyDecisionRepository.save(policyDecision("dk-1", "hash-1")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * SPEC-PG-008 / INV-PG-002 ("Policy Decisions Must Be Explainable"): the
     * only prior coverage of {@code constraints} always saved an empty
     * list, so a real serialization/mapping bug on this {@code jsonb}
     * column (07-data-model §policy_decisions) could have gone undetected —
     * a recurring bug category in this codebase's other domains. Exercises
     * a non-empty, multi-entry list through the real Postgres round trip.
     */
    @Test
    void policyDecisionConstraintsRoundTripThroughPostgresJsonb() {
        List<Constraint> constraints = List.of(
            new Constraint(Constraint.Type.TIME_WINDOW, "business-hours"),
            new Constraint(Constraint.Type.READ_ONLY, "true")
        );
        PolicyDecision decision = policyDecision("dk-constraints", "hash-constraints", constraints);
        policyDecisionRepository.save(decision);

        PolicyDecision loaded = policyDecisionRepository.findById(decision.policyDecisionId()).orElseThrow();

        assertThat(loaded.constraints()).containsExactlyElementsOf(constraints);
    }

    @Test
    void approvalRequestEnforcesUniqueSourceRequestKey() {
        ApprovalRequest request = approvalRequest("ar-1", "rk-1", "src-req-1");
        approvalRequestRepository.save(request);

        assertThatThrownBy(() -> approvalRequestRepository.save(approvalRequest("ar-2", "rk-1", "src-req-1")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * SPEC-PG-009 / 01-domain-model §Aggregate Boundary: the ApprovalRequest
     * -> PolicyDecision back-reference round-trips through Postgres, and
     * the real FK (migration V012) rejects a reference to a policy decision
     * that doesn't exist rather than silently accepting a dangling id.
     */
    @Test
    void approvalRequestPolicyDecisionLinkRoundTripsAndIsForeignKeyEnforced() {
        PolicyDecision decision = policyDecision("dk-linked", "hash-linked");
        policyDecisionRepository.save(decision);

        ApprovalRequest request = ApprovalRequest.requested(
            "ar-linked", "rk-linked", "hash-1", "tool-gateway", "src-req-linked", null, null, "tool-req-1",
            decision.policyDecisionId(), "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        approvalRequestRepository.save(request);

        ApprovalRequest loaded = approvalRequestRepository.findById("ar-linked").orElseThrow();
        assertThat(loaded.policyDecisionId()).isEqualTo(decision.policyDecisionId());

        ApprovalRequest dangling = ApprovalRequest.requested(
            "ar-dangling", "rk-dangling", "hash-1", "tool-gateway", "src-req-dangling", null, null, "tool-req-1",
            "does-not-exist", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        assertThatThrownBy(() -> approvalRequestRepository.save(dangling))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvalRequestExpiryQueryOnlyReturnsRequestedPastThreshold() {
        Instant now = Instant.now();
        approvalRequestRepository.save(approvalRequestWithExpiry("ar-3", "rk-3", "src-req-3", now.minusSeconds(10)));
        approvalRequestRepository.save(approvalRequestWithExpiry("ar-4", "rk-4", "src-req-4", now.plusSeconds(3600)));

        List<ApprovalRequest> due = approvalRequestRepository.findRequestedExpiringBefore(now);

        assertThat(due).extracting(ApprovalRequest::approvalRequestId).containsExactly("ar-3");
    }

    @Test
    void governanceAuditRecordsAreQueryableByCorrelationId() {
        governanceAuditRepository.append(auditRecord("corr-shared"));
        governanceAuditRepository.append(auditRecord("corr-other"));

        List<GovernanceAuditRecord> records = governanceAuditRepository.findByCorrelationId("corr-shared");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).correlationId()).isEqualTo("corr-shared");
    }

    @Test
    void outboxEventsAndProcessedEventsEnforceTheirUniqueConstraints() {
        UUID outboxId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO governance.outbox_events "
                + "(outbox_id, aggregate_type, aggregate_id, event_type, event_version, correlation_id, occurred_at) "
                + "VALUES (?, 'ApprovalRequest', 'ar-1', 'approval.requested.v1', 'v1', 'corr-1', now())",
            outboxId
        );
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM governance.outbox_events WHERE outbox_id = ?", String.class, outboxId
        )).isEqualTo("PENDING");

        jdbcTemplate.update(
            "INSERT INTO governance.processed_events (id, event_id, consumer_name, processed_at) VALUES (?, ?, ?, now())",
            UUID.randomUUID(), "evt-1", "consumer-a"
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO governance.processed_events (id, event_id, consumer_name, processed_at) VALUES (?, ?, ?, now())",
            UUID.randomUUID(), "evt-1", "consumer-a"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The {@code UNIQUE (approval_request_id)} constraint on {@code
     * approval_decisions} is what makes concurrent grant/deny safe: exactly
     * one of the two racing inserts must succeed, and the other must fail
     * with a constraint violation rather than silently overwriting the
     * winner (test-plan §Integration Tests: "concurrent approval grant/deny").
     */
    @Test
    void concurrentDecisionsForTheSameApprovalRequestOnlyLetOneWin() throws Exception {
        ApprovalRequest request = approvalRequest("ar-race", "rk-race", "src-req-race");
        approvalRequestRepository.save(request);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> grant = decisionAttempt(request.approvalRequestId(), "approver-a", ApprovalDecision.Outcome.APPROVED);
            Callable<Boolean> deny = decisionAttempt(request.approvalRequestId(), "approver-b", ApprovalDecision.Outcome.DENIED);

            List<Future<Boolean>> results = executor.invokeAll(List.of(grant, deny));
            long successes = results.stream().filter(f -> get(f)).count();

            assertThat(successes).isEqualTo(1);
            assertThat(approvalDecisionRepository.findByApprovalRequestId(request.approvalRequestId())).isPresent();
        } finally {
            executor.shutdown();
        }
    }

    private Callable<Boolean> decisionAttempt(String approvalRequestId, String decidedBy, ApprovalDecision.Outcome outcome) {
        return () -> {
            try {
                ApprovalDecision decision = new ApprovalDecision(
                    UUID.randomUUID().toString(), approvalRequestId, outcome, decidedBy, Instant.now(),
                    "race test", List.of(), outcome == ApprovalDecision.Outcome.APPROVED, "cik-" + decidedBy
                );
                approvalDecisionRepository.save(decision);
                return true;
            } catch (DataIntegrityViolationException e) {
                return false;
            }
        };
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PolicyDecision policyDecision(String decisionKey, String inputHash) {
        return policyDecision(decisionKey, inputHash, List.of());
    }

    private PolicyDecision policyDecision(String decisionKey, String inputHash, List<Constraint> constraints) {
        return new PolicyDecision(
            UUID.randomUUID().toString(), decisionKey, inputHash, "user", "user-1", "READ",
            "ticket", "ticket-1", "tenant-1", "tool-gateway", "src-req-1", "ticket-1", null,
            DecisionEffect.ALLOW, RiskLevel.LOW, false, false, constraints, List.of(ReasonCode.POLICY_MATCHED),
            "policy-1", "1", Instant.now(), null
        );
    }

    private ApprovalRequest approvalRequest(String id, String requestKey, String sourceRequestId) {
        return ApprovalRequest.requested(
            id, requestKey, "hash-1", "tool-gateway", sourceRequestId, null, null, "tool-req-1", null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    private ApprovalRequest approvalRequestWithExpiry(String id, String requestKey, String sourceRequestId, Instant expiresAt) {
        return ApprovalRequest.requested(
            id, requestKey, "hash-1", "tool-gateway", sourceRequestId, null, null, "tool-req-1", null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), expiresAt, Instant.now()
        );
    }

    private GovernanceAuditRecord auditRecord(String correlationId) {
        return new GovernanceAuditRecord(
            UUID.randomUUID().toString(), GovernanceAuditRecord.Action.DECISION_EVALUATED, "actor-1",
            "tool-gateway", "src-req-1", "policy-1", "1", "reason", correlationId, null, "hash", Instant.now()
        );
    }
}
