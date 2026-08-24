package com.opsmind.policygovernance.infrastructure.persistence;

import com.opsmind.policygovernance.application.port.ApprovalDecisionRepository;
import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.application.port.GovernanceAuditRepository;
import com.opsmind.policygovernance.application.port.PolicyDecisionRepository;
import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
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
    private com.opsmind.policygovernance.application.port.OutboxEventRepository outboxEventRepository;

    @Autowired
    private com.opsmind.policygovernance.application.port.ProcessedEventRepository processedEventRepository;

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

    /**
     * SPEC-PG-019 (goal: "rule fixes require new versions"): {@code
     * findLatestVersion} — what {@code PolicyAdminService#draft} uses to
     * compute the next version number — returns the true highest-numbered
     * version against a real Postgres instance, regardless of that
     * version's own status, and {@code uq_policy_versions_policy_version}
     * rejects a second row that reuses a version number for the same
     * policy.
     */
    @Test
    void policyVersionFindLatestVersionReturnsTheHighestNumberedVersionRegardlessOfStatus() {
        Policy policy = Policy.created("policy-versioned", "Versioned Policy", "global", "author-1", Instant.now());
        policyRepository.save(policy);
        PolicyRule rule = new PolicyRule("rule-1", List.of(), DecisionEffect.ALLOW, RiskLevel.LOW, false, List.of());

        PolicyVersion v1 = PolicyVersion.draft("pv-versioned-1", "policy-versioned", 1, List.of(rule), "author-1")
            .transitionTo(PolicyStatus.REVIEWING, "reviewer-1", null, Instant.now())
            .transitionTo(PolicyStatus.PUBLISHED, "publisher-1", Instant.now(), Instant.now());
        policyVersionRepository.save(v1);
        assertThat(policyVersionRepository.findLatestVersion("policy-versioned").orElseThrow().versionNumber()).isEqualTo(1);

        PolicyVersion v2 = PolicyVersion.draft("pv-versioned-2", "policy-versioned", 2, List.of(rule), "author-1");
        policyVersionRepository.save(v2);

        PolicyVersion latest = policyVersionRepository.findLatestVersion("policy-versioned").orElseThrow();
        assertThat(latest.versionNumber()).isEqualTo(2);
        assertThat(latest.status()).isEqualTo(PolicyStatus.DRAFT);

        PolicyVersion duplicateVersionNumber = PolicyVersion.draft("pv-versioned-dup", "policy-versioned", 2, List.of(rule), "author-1");
        assertThatThrownBy(() -> policyVersionRepository.save(duplicateVersionNumber))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void policyDecisionEnforcesUniqueDecisionKeyAndInputHash() {
        PolicyDecision decision = policyDecision("dk-1", "hash-1");
        policyDecisionRepository.save(decision);

        assertThatThrownBy(() -> policyDecisionRepository.save(policyDecision("dk-1", "hash-1")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** SPEC-PG-021 (migration V020, 10-failure-handling §Degraded Policy Mode): {@code degraded} round-trips through the real column. */
    @Test
    void policyDecisionDegradedFlagRoundTripsThroughPostgres() {
        PolicyDecision normal = policyDecision("dk-normal", "hash-normal");
        policyDecisionRepository.save(normal);
        assertThat(policyDecisionRepository.findById(normal.policyDecisionId()).orElseThrow().degraded()).isFalse();

        PolicyDecision degraded = new PolicyDecision(
            UUID.randomUUID().toString(), "dk-degraded", "hash-degraded", "user", "user-1", "READ",
            "ticket", "ticket-1", "tenant-1", "tool-gateway", "src-req-1", "ticket-1", null,
            DecisionEffect.DENY, RiskLevel.HIGH, false, true, List.of(), List.of(ReasonCode.EVALUATOR_UNAVAILABLE),
            "policy-1", "NONE", Instant.now(), null, true
        );
        policyDecisionRepository.save(degraded);
        assertThat(policyDecisionRepository.findById(degraded.policyDecisionId()).orElseThrow().degraded()).isTrue();
    }

    /** SPEC-PG-033 (goal: "poison decision review"): finds every {@code evaluationFailed} decision through the real Postgres column, never a healthy one. */
    @Test
    void policyDecisionFindEvaluationFailedReturnsOnlyPoisonDecisionsThroughPostgres() {
        policyDecisionRepository.save(policyDecision("dk-healthy-poison-check", "hash-healthy"));
        PolicyDecision poison = new PolicyDecision(
            UUID.randomUUID().toString(), "dk-poison", "hash-poison", "user", "user-1", "READ",
            "ticket", "ticket-1", "tenant-1", "tool-gateway", "src-req-poison", "ticket-1", null,
            DecisionEffect.DENY, RiskLevel.HIGH, false, true, List.of(), List.of(ReasonCode.EVALUATOR_UNAVAILABLE),
            "policy-1", "NONE", Instant.now(), null, true
        );
        policyDecisionRepository.save(poison);

        assertThat(policyDecisionRepository.findEvaluationFailed())
            .extracting(PolicyDecision::policyDecisionId)
            .containsExactly(poison.policyDecisionId());
    }

    /** SPEC-PG-033 (goal: "startup recovery workers" — 10-failure-handling §Recovery: "check policy version consistency"): every policy header through the real Postgres table. */
    @Test
    void policyFindAllReturnsEveryPolicyThroughPostgres() {
        policyRepository.save(Policy.created("policy-recovery-1", "Recovery Policy 1", "global", "author-1", Instant.now()));
        policyRepository.save(Policy.created("policy-recovery-2", "Recovery Policy 2", "global", "author-1", Instant.now()));

        assertThat(policyRepository.findAll())
            .extracting(Policy::policyId)
            .contains("policy-recovery-1", "policy-recovery-2");
    }

    /** SPEC-PG-033 (goal: "poison decision review" for outbox rows): finds every dead-lettered row through the real Postgres column, never a PENDING/PUBLISHED one. */
    @Test
    void outboxFindFailedReturnsOnlyDeadLetteredRowsThroughPostgres() {
        String pendingId = UUID.randomUUID().toString();
        String failedId = UUID.randomUUID().toString();
        outboxEventRepository.append(new com.opsmind.policygovernance.application.model.OutboxEventRecord(
            pendingId, "PolicyDecision", "pd-1", "policy.decision.created.v1", "v1", "{}", "corr-1", null,
            com.opsmind.policygovernance.application.model.OutboxEventStatus.PENDING, 0, null, null, Instant.now()
        ));
        outboxEventRepository.append(new com.opsmind.policygovernance.application.model.OutboxEventRecord(
            failedId, "PolicyDecision", "pd-2", "policy.decision.created.v1", "v1", "{}", "corr-2", null,
            com.opsmind.policygovernance.application.model.OutboxEventStatus.FAILED, 5, null, null, Instant.now()
        ));

        assertThat(outboxEventRepository.findFailed())
            .extracting(com.opsmind.policygovernance.application.model.OutboxEventRecord::outboxId)
            .containsExactly(failedId);
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
            "ar-linked", "rk-linked", "hash-1", "tool-gateway", "src-req-linked", null, null, "tool-req-1", null,
            decision.policyDecisionId(), "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        approvalRequestRepository.save(request);

        ApprovalRequest loaded = approvalRequestRepository.findById("ar-linked").orElseThrow();
        assertThat(loaded.policyDecisionId()).isEqualTo(decision.policyDecisionId());

        ApprovalRequest dangling = ApprovalRequest.requested(
            "ar-dangling", "rk-dangling", "hash-1", "tool-gateway", "src-req-dangling", null, null, "tool-req-1", null,
            "does-not-exist", "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        assertThatThrownBy(() -> approvalRequestRepository.save(dangling))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * SPEC-PG-012 (migration V014): cancel's own idempotency key round-trips
     * through the real {@code cancel_command_idempotency_key} column, and is
     * still {@code null} on a request nobody has cancelled — mirroring how
     * SPEC-PG-011 exercised {@code command_idempotency_key} on {@code
     * approval_decisions}.
     */
    @Test
    void approvalRequestCancelCommandIdempotencyKeyRoundTripsThroughPostgres() {
        ApprovalRequest request = approvalRequest("ar-cancel", "rk-cancel", "src-req-cancel");
        approvalRequestRepository.save(request);
        assertThat(approvalRequestRepository.findById("ar-cancel").orElseThrow().cancelCommandIdempotencyKey()).isNull();

        ApprovalRequest cancelled = request.cancel(Instant.now(), "cik-cancel-1");
        approvalRequestRepository.save(cancelled);

        ApprovalRequest loaded = approvalRequestRepository.findById("ar-cancel").orElseThrow();
        assertThat(loaded.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(loaded.cancelCommandIdempotencyKey()).isEqualTo("cik-cancel-1");
    }

    /** SPEC-PG-015 (migration V016): {@code executor_id} round-trips through the real column, nullable when the caller never supplied one. */
    @Test
    void approvalRequestExecutorIdRoundTripsThroughPostgres() {
        ApprovalRequest withExecutor = ApprovalRequest.requested(
            "ar-executor", "rk-executor", "hash-1", "tool-gateway", "src-req-executor", null, null, "tool-req-1",
            "executor-1", null, "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
        approvalRequestRepository.save(withExecutor);
        assertThat(approvalRequestRepository.findById("ar-executor").orElseThrow().executorId()).isEqualTo("executor-1");

        approvalRequestRepository.save(approvalRequest("ar-no-executor", "rk-no-executor", "src-req-no-executor"));
        assertThat(approvalRequestRepository.findById("ar-no-executor").orElseThrow().executorId()).isNull();
    }

    /**
     * SPEC-PG-025: {@code ProcessedEventRepository#markProcessedIfNew}
     * relies on the real {@code uq_processed_events_event_consumer}
     * constraint — the first call for a given {@code (eventId,
     * consumerName)} pair succeeds and returns {@code true}; a second call
     * with the same pair hits the constraint and returns {@code false},
     * mirroring {@code ConsumedEventDeduplicationServiceTest}'s own
     * in-memory coverage but against a real Postgres instance.
     */
    @Test
    void processedEventMarkProcessedIfNewRoundTripsThroughPostgres() {
        boolean first = processedEventRepository.markProcessedIfNew("evt-pg-1", "consumer-a", "tool.approval.required.v1");
        boolean second = processedEventRepository.markProcessedIfNew("evt-pg-1", "consumer-a", "tool.approval.required.v1");

        assertThat(first).isTrue();
        assertThat(second).isFalse();

        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM governance.processed_events WHERE event_id = ? AND consumer_name = ?",
            Integer.class, "evt-pg-1", "consumer-a"
        );
        assertThat(rows).isEqualTo(1);
    }

    /** The same eventId is independently tracked per consumer_name. */
    @Test
    void processedEventDedupIsIndependentPerConsumer() {
        boolean forConsumerA = processedEventRepository.markProcessedIfNew("evt-pg-2", "consumer-a", "tool.approval.required.v1");
        boolean forConsumerB = processedEventRepository.markProcessedIfNew("evt-pg-2", "consumer-b", "tool.approval.required.v1");

        assertThat(forConsumerA).isTrue();
        assertThat(forConsumerB).isTrue();
    }

    /**
     * SPEC-PG-034 (goal: "admin-safe repair flow for governance event
     * replay/backfill"): {@code findByEventId} finds every consumer that
     * processed a given event through the real Postgres columns, and
     * {@code deleteIfExists} — the "backfill" repair action — actually
     * removes the row (returning {@code true}), after which the same
     * {@code (eventId, consumerName)} pair is accepted as new again by
     * {@code markProcessedIfNew}, and a second {@code deleteIfExists} call
     * against the now-absent row returns {@code false}.
     */
    @Test
    void processedEventFindByEventIdAndDeleteIfExistsRoundTripThroughPostgres() {
        processedEventRepository.markProcessedIfNew("evt-pg-3", "consumer-a", "tool.approval.required.v1");
        processedEventRepository.markProcessedIfNew("evt-pg-3", "consumer-b", "tool.approval.required.v1");

        assertThat(processedEventRepository.findByEventId("evt-pg-3"))
            .extracting(com.opsmind.policygovernance.application.model.ProcessedEventRecord::consumerName)
            .containsExactlyInAnyOrder("consumer-a", "consumer-b");

        boolean deleted = processedEventRepository.deleteIfExists("evt-pg-3", "consumer-a");
        assertThat(deleted).isTrue();
        assertThat(processedEventRepository.findByEventId("evt-pg-3"))
            .extracting(com.opsmind.policygovernance.application.model.ProcessedEventRecord::consumerName)
            .containsExactly("consumer-b");
        assertThat(processedEventRepository.markProcessedIfNew("evt-pg-3", "consumer-a", "tool.approval.required.v1"))
            .as("the marker was deleted, so this must be accepted as new again")
            .isTrue();

        assertThat(processedEventRepository.deleteIfExists("evt-pg-3", "no-such-consumer")).isFalse();
    }

    /**
     * SPEC-PG-024: {@code OutboxEventRepository#findById}/{@code #requeue}
     * round-trip through the real {@code outbox_events} table — a
     * dead-lettered row's {@code attempt_count} resets to {@code 0} and its
     * status moves back to {@code PENDING}, mirroring {@code
     * OutboxDispatchServiceTest}'s own in-memory coverage but against a real
     * Postgres instance.
     */
    @Test
    void outboxEventFindByIdAndRequeueRoundTripThroughPostgres() {
        String outboxId = java.util.UUID.randomUUID().toString();
        outboxEventRepository.append(new com.opsmind.policygovernance.application.model.OutboxEventRecord(
            outboxId, "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            com.opsmind.policygovernance.application.model.OutboxEventStatus.FAILED, 4, null, null, Instant.now()
        ));
        assertThat(outboxEventRepository.findById(outboxId).orElseThrow().status())
            .isEqualTo(com.opsmind.policygovernance.application.model.OutboxEventStatus.FAILED);

        outboxEventRepository.requeue(outboxId, Instant.now());

        var requeued = outboxEventRepository.findById(outboxId).orElseThrow();
        assertThat(requeued.status()).isEqualTo(com.opsmind.policygovernance.application.model.OutboxEventStatus.PENDING);
        assertThat(requeued.attemptCount()).isZero();
    }

    /** {@code findById} returns empty for an id nothing has ever staged. */
    @Test
    void outboxEventFindByIdReturnsEmptyWhenNoSuchRowExists() {
        assertThat(outboxEventRepository.findById(java.util.UUID.randomUUID().toString())).isEmpty();
    }

    /**
     * SPEC-PG-023 (migration V022): the three new ticket-exception
     * ApprovalType values round-trip through the real {@code
     * ck_approval_requests_approval_type} CHECK constraint, mirroring
     * {@link #approvalRequestExecutorIdRoundTripsThroughPostgres}.
     */
    @Test
    void ticketExceptionApprovalTypesRoundTripThroughPostgres() {
        for (ApprovalType type : List.of(
            ApprovalType.TICKET_SLA_EXCEPTION, ApprovalType.TICKET_CLOSURE_OVERRIDE, ApprovalType.TICKET_ESCALATION_EXCEPTION
        )) {
            String id = "ar-" + type.name().toLowerCase();
            ApprovalRequest request = ApprovalRequest.requested(
                id, "rk-" + type.name().toLowerCase(), "hash-1", "ticket-workflow", "src-req-" + type.name().toLowerCase(),
                "ticket-1", null, null, null, null, "requester-1", type, RiskLevel.HIGH, List.of(),
                Instant.now().plusSeconds(3600), Instant.now()
            );
            approvalRequestRepository.save(request);

            assertThat(approvalRequestRepository.findById(id).orElseThrow().approvalType()).isEqualTo(type);
        }
    }

    /**
     * SPEC-PG-022 (migration V021): {@code used_command_idempotency_key} and
     * the {@code USED} status round-trip through the real columns, mirroring
     * {@link #approvalRequestCancelCommandIdempotencyKeyRoundTripsThroughPostgres}.
     */
    @Test
    void approvalRequestUsedCommandIdempotencyKeyRoundTripsThroughPostgres() {
        ApprovalRequest request = overrideRequest("ar-override-use", "rk-override-use", "src-req-override-use");
        approvalRequestRepository.save(request);
        ApprovalDecision decision = new ApprovalDecision(
            UUID.randomUUID().toString(), request.approvalRequestId(), ApprovalDecision.Outcome.APPROVED, "approver-1",
            Instant.now(), "looks fine", List.of(), true, "cik-override-use", null, null, true
        );
        ApprovalRequest approved = request.approve(decision, Instant.now());
        approvalRequestRepository.save(approved);
        assertThat(approvalRequestRepository.findById("ar-override-use").orElseThrow().usedCommandIdempotencyKey()).isNull();

        ApprovalRequest used = approved.use(Instant.now(), "use-cik-1");
        approvalRequestRepository.save(used);

        ApprovalRequest loaded = approvalRequestRepository.findById("ar-override-use").orElseThrow();
        assertThat(loaded.status()).isEqualTo(ApprovalStatus.USED);
        assertThat(loaded.usedCommandIdempotencyKey()).isEqualTo("use-cik-1");
    }

    /** SPEC-PG-022 (migration V021): {@code revoked_command_idempotency_key} and the {@code REVOKED} status round-trip through the real columns. */
    @Test
    void approvalRequestRevokedCommandIdempotencyKeyRoundTripsThroughPostgres() {
        ApprovalRequest request = overrideRequest("ar-override-revoke", "rk-override-revoke", "src-req-override-revoke");
        approvalRequestRepository.save(request);
        ApprovalDecision decision = new ApprovalDecision(
            UUID.randomUUID().toString(), request.approvalRequestId(), ApprovalDecision.Outcome.APPROVED, "approver-1",
            Instant.now(), "looks fine", List.of(), true, "cik-override-revoke", null, null, true
        );
        ApprovalRequest approved = request.approve(decision, Instant.now());
        approvalRequestRepository.save(approved);

        ApprovalRequest revoked = approved.revoke(Instant.now(), "revoke-cik-1");
        approvalRequestRepository.save(revoked);

        ApprovalRequest loaded = approvalRequestRepository.findById("ar-override-revoke").orElseThrow();
        assertThat(loaded.status()).isEqualTo(ApprovalStatus.REVOKED);
        assertThat(loaded.revokedCommandIdempotencyKey()).isEqualTo("revoke-cik-1");
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

    /**
     * SPEC-PG-030 (goal: "governance audit chain queries by
     * ticket/source/decision/approval/policy"): the 3 new linkage columns
     * (migration V024) round-trip through the real Postgres columns, and
     * each of the 5 new query dimensions finds only the record that
     * actually carries the matching linkage id.
     */
    @Test
    void governanceAuditRecordsAreQueryableByTicketApprovalDecisionSourceAndPolicy() {
        governanceAuditRepository.append(new GovernanceAuditRecord(
            UUID.randomUUID().toString(), GovernanceAuditRecord.Action.DECISION_EVALUATED, "actor-1",
            "tool-gateway", "src-req-linked", "policy-linked", "1", "reason", "corr-linked", null, "hash-linked",
            Instant.now(), null,
            "ticket-linked", "ar-linked", "pd-linked", null
        ));
        governanceAuditRepository.append(new GovernanceAuditRecord(
            UUID.randomUUID().toString(), GovernanceAuditRecord.Action.POLICY_DRAFTED, "actor-2",
            "06", "policy-other", "policy-other", "1", "reason", "corr-other", null, "hash-other",
            Instant.now(), null,
            null, null, null, null
        ));

        assertThat(governanceAuditRepository.findByTicketId("ticket-linked"))
            .extracting(GovernanceAuditRecord::auditRecordId).hasSize(1);
        assertThat(governanceAuditRepository.findByApprovalRequestId("ar-linked"))
            .extracting(GovernanceAuditRecord::auditRecordId).hasSize(1);
        assertThat(governanceAuditRepository.findByPolicyDecisionId("pd-linked"))
            .extracting(GovernanceAuditRecord::auditRecordId).hasSize(1);
        assertThat(governanceAuditRepository.findBySourceRequestId("src-req-linked"))
            .extracting(GovernanceAuditRecord::auditRecordId).hasSize(1);
        assertThat(governanceAuditRepository.findByPolicyId("policy-linked"))
            .extracting(GovernanceAuditRecord::auditRecordId).hasSize(1);

        assertThat(governanceAuditRepository.findByTicketId("no-such-ticket")).isEmpty();
    }

    /**
     * SPEC-PG-031 (11-security §Tamper-Resistant Audit: "may only be
     * archived by retention policy", migration V025). {@code archived_at}
     * round-trips through the real column, {@code archiveRecordedBefore}
     * only touches the row older than the cutoff (a real bulk {@code
     * UPDATE} against Postgres, not the in-memory test double), and {@code
     * findAllOrderedByRecordedAt} returns every row in true {@code
     * recorded_at} order.
     */
    @Test
    void governanceAuditRecordsAreArchivedByRetentionPolicyThroughPostgres() {
        Instant now = Instant.now();
        governanceAuditRepository.append(new GovernanceAuditRecord(
            "old-1", GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-1", "tool-gateway",
            "src-req-old", null, null, "reason", "corr-old", null, "hash-old",
            now.minusSeconds(200 * 24 * 3600L), null, null, null, null, null
        ));
        governanceAuditRepository.append(new GovernanceAuditRecord(
            "new-1", GovernanceAuditRecord.Action.APPROVAL_REQUESTED, "actor-2", "tool-gateway",
            "src-req-new", null, null, "reason", "corr-new", null, "hash-new",
            now, null, null, null, null, null
        ));

        int archivedCount = governanceAuditRepository.archiveRecordedBefore(now.minusSeconds(30 * 24 * 3600L), now);

        assertThat(archivedCount).isEqualTo(1);
        List<GovernanceAuditRecord> ordered = governanceAuditRepository.findAllOrderedByRecordedAt();
        assertThat(ordered).extracting(GovernanceAuditRecord::auditRecordId).containsExactly("old-1", "new-1");
        assertThat(ordered.get(0).archivedAt()).isNotNull();
        assertThat(ordered.get(1).archivedAt()).isNull();

        // A second run over the same cutoff must not re-touch (or re-count) the already-archived row.
        int secondRunCount = governanceAuditRepository.archiveRecordedBefore(now.minusSeconds(30 * 24 * 3600L), now);
        assertThat(secondRunCount).isZero();
    }

    /**
     * SPEC-PG-017 (migration V018, 11-security §Tamper-Resistant Audit).
     * {@code previous_hash} round-trips through the real column, and {@code
     * findMostRecentIntegrityHash} — the lookup the hash chain relies on —
     * returns the true most recent record's own hash against a real
     * Postgres instance (ordered by {@code recorded_at}, unlike the
     * in-memory test double's insertion-order fallback).
     */
    @Test
    void governanceAuditPreviousHashRoundTripsAndChainsThroughPostgres() {
        assertThat(governanceAuditRepository.findMostRecentIntegrityHash()).isEmpty();

        GovernanceAuditRecord first = governanceAuditRepository.append(auditRecord("corr-chain").withIntegrityHash("hash-first"));
        assertThat(governanceAuditRepository.findMostRecentIntegrityHash()).contains("hash-first");

        GovernanceAuditRecord second = new GovernanceAuditRecord(
            UUID.randomUUID().toString(), GovernanceAuditRecord.Action.DECISION_EVALUATED, "actor-1",
            "tool-gateway", "src-req-1", "policy-1", "1", "reason", "corr-chain", null, "hash-second",
            first.recordedAt().plusSeconds(1), first.integrityHash(),
            null, null, null, null
        );
        governanceAuditRepository.append(second);

        assertThat(governanceAuditRepository.findMostRecentIntegrityHash()).contains("hash-second");
        List<GovernanceAuditRecord> chained = governanceAuditRepository.findByCorrelationId("corr-chain");
        assertThat(chained).extracting(GovernanceAuditRecord::previousHash).containsExactlyInAnyOrder(null, "hash-first");
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
    /** SPEC-PG-016 (migration V017, 11-security §Approval Authenticity): session/device/step-up round-trip through the real columns. */
    @Test
    void approvalDecisionAuthenticityFieldsRoundTripThroughPostgres() {
        ApprovalRequest request = approvalRequest("ar-authenticity", "rk-authenticity", "src-req-authenticity");
        approvalRequestRepository.save(request);
        ApprovalDecision decision = new ApprovalDecision(
            UUID.randomUUID().toString(), request.approvalRequestId(), ApprovalDecision.Outcome.APPROVED, "approver-1",
            Instant.now(), "looks fine", List.of(), true, "cik-authenticity", "session-1", "device-1", true
        );

        approvalDecisionRepository.save(decision);

        ApprovalDecision loaded = approvalDecisionRepository.findByApprovalRequestId(request.approvalRequestId()).orElseThrow();
        assertThat(loaded.sessionId()).isEqualTo("session-1");
        assertThat(loaded.deviceId()).isEqualTo("device-1");
        assertThat(loaded.stepUpVerified()).isTrue();
    }

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
                    "race test", List.of(), outcome == ApprovalDecision.Outcome.APPROVED, "cik-" + decidedBy, null, null, false
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
            "policy-1", "1", Instant.now(), null, false
        );
    }

    private ApprovalRequest approvalRequest(String id, String requestKey, String sourceRequestId) {
        return ApprovalRequest.requested(
            id, requestKey, "hash-1", "tool-gateway", sourceRequestId, null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    /** SPEC-PG-022: a valid POLICY_OVERRIDE request — non-null expiresAt and a non-empty constraint list, both required by UC-PG-006. */
    private ApprovalRequest overrideRequest(String id, String requestKey, String sourceRequestId) {
        return ApprovalRequest.requested(
            id, requestKey, "hash-1", "tool-gateway", sourceRequestId, null, null, null, null, null,
            "requester-1", ApprovalType.POLICY_OVERRIDE, RiskLevel.CRITICAL,
            List.of(new Constraint(Constraint.Type.TIME_WINDOW, "read-only for 1 hour")),
            Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    private ApprovalRequest approvalRequestWithExpiry(String id, String requestKey, String sourceRequestId, Instant expiresAt) {
        return ApprovalRequest.requested(
            id, requestKey, "hash-1", "tool-gateway", sourceRequestId, null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), expiresAt, Instant.now()
        );
    }

    private GovernanceAuditRecord auditRecord(String correlationId) {
        return new GovernanceAuditRecord(
            UUID.randomUUID().toString(), GovernanceAuditRecord.Action.DECISION_EVALUATED, "actor-1",
            "tool-gateway", "src-req-1", "policy-1", "1", "reason", correlationId, null, "hash", Instant.now(), null,
            "ticket-1", "ar-1", "pd-1", null
        );
    }
}
