package com.opsmind.identity.infrastructure.persistence;

import com.opsmind.identity.application.model.OutboxEventRecord;
import com.opsmind.identity.application.model.OutboxEventStatus;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.OutboxEventRepository;
import com.opsmind.identity.application.port.out.RoleAssignmentRepository;
import com.opsmind.identity.application.port.out.StepUpChallengeRepository;
import com.opsmind.identity.application.port.out.UserIdentityRepository;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.session.AuthenticationAssurance;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.support.PostgresContainerSupport;
import com.opsmind.identity.support.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
 * SPEC-UA-002/SPEC-UA-003/SPEC-UA-012/SPEC-UA-017 (test-plan §Integration
 * Tests). Exercises the real JPA adapters against a Testcontainers
 * PostgreSQL instance running the actual Flyway migrations — not the
 * in-memory test doubles the application-layer unit tests use — and, in
 * particular, the two 03-state-machine guarantees that only a real
 * database can honor: the {@code uq_role_assignments_active} partial
 * unique index and {@link StepUpChallengeRepository#tryConsume}'s atomic
 * conditional update.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class IdentityPersistenceIT implements PostgresContainerSupport {

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private StepUpChallengeRepository stepUpChallengeRepository;

    @Autowired
    private AuditPort auditPort;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void userIdentityRoundTripsAndEnforcesTheNaturalIdentityKey() {
        TenantId tenantId = new TenantId("tenant-" + UUID.randomUUID());
        ExternalSubject externalSubject = new ExternalSubject("https://idp.example", "sub-" + UUID.randomUUID());
        UserIdentity linked = UserIdentity.link(UUID.randomUUID().toString(), tenantId, externalSubject, "alice", "Alice", "alice@example.com", IdentityType.HUMAN, NOW);

        userIdentityRepository.save(linked);
        UserIdentity found = userIdentityRepository.findByExternalSubject(tenantId.value(), externalSubject).orElseThrow();

        assertThat(found.userIdentityId()).isEqualTo(linked.userIdentityId());
        assertThat(found.status()).isEqualTo(UserStatus.ACTIVE);
    }

    /** SPEC-UA-034 (07-data-model §user_sessions: "UNIQUE session hash"). The real constraint {@code ManageSessionService#start}'s own defense-in-depth catch relies on. */
    @Test
    void duplicateTokenIdHashIsRejectedByTheRealDatabaseConstraint() {
        String tokenIdHash = "token-hash-" + UUID.randomUUID();
        UserSession first = UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-" + UUID.randomUUID()),
            "idp-hash", tokenIdHash, "client-1", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW), "device-hash",
            NOW, NOW.plusSeconds(3600)
        );
        userSessionRepository.save(first);

        UserSession second = UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-" + UUID.randomUUID()),
            "idp-hash", tokenIdHash, "client-1", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW), "device-hash",
            NOW, NOW.plusSeconds(3600)
        );
        assertThatThrownBy(() -> userSessionRepository.save(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 03-state-machine: "Overlapping ACTIVE assignments for the same user, role, and scope are prevented by constraint." */
    @Test
    void roleAssignmentActiveOverlapIsRejectedByTheRealDatabaseConstraint() {
        String userIdentityId = seedUser();
        ResourceScope scope = ResourceScope.tenantWide();
        RoleAssignment first = RoleAssignment.grantActive(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), userIdentityId, RoleCode.SUPPORT_AGENT, scope, List.of(), null, "admin-1", null, NOW
        );
        roleAssignmentRepository.save(first);

        RoleAssignment overlapping = RoleAssignment.grantActive(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), userIdentityId, RoleCode.SUPPORT_AGENT, scope, List.of(), null, "admin-1", null, NOW
        );
        assertThatThrownBy(() -> roleAssignmentRepository.save(overlapping)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roleAssignmentPendingActivateCancelRoundTrips() {
        String userIdentityId = seedUser();
        Instant validFrom = NOW.plusSeconds(3600);
        RoleAssignment pending = RoleAssignment.grantPending(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), userIdentityId, RoleCode.APPROVER, ResourceScope.tenantWide(), List.of(),
            validFrom, null, "admin-1", "scheduled", NOW
        );
        roleAssignmentRepository.save(pending);

        assertThat(roleAssignmentRepository.findPendingDue(NOW)).isEmpty();
        List<RoleAssignment> due = roleAssignmentRepository.findPendingDue(validFrom);
        assertThat(due).extracting(RoleAssignment::roleAssignmentId).contains(pending.roleAssignmentId());

        RoleAssignment activated = due.stream().filter(a -> a.roleAssignmentId().equals(pending.roleAssignmentId())).findFirst().orElseThrow().activate(validFrom);
        roleAssignmentRepository.save(activated);
        assertThat(roleAssignmentRepository.findById(pending.roleAssignmentId()).orElseThrow().status().name()).isEqualTo("ACTIVE");
    }

    /** 09-concurrency-and-idempotency: "Step-up consumption uses atomic conditional update ... at most one request moves VERIFIED to CONSUMED." */
    @Test
    void stepUpTryConsumeAllowsOnlyOneConcurrentWinner() throws Exception {
        String userSessionId = seedSession();
        StepUpChallenge verified = StepUpChallenge.request(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-1"),
            userSessionId, new AuthorizationTarget("approval:decide", "approval", "ap-1"), "AAL2", List.of("otp"), 3, "corr-1", NOW, NOW.plusSeconds(300)
        ).dispatch("nonce-hash-" + UUID.randomUUID(), NOW).verify("proof-hash-" + UUID.randomUUID(), NOW.plusSeconds(1));
        stepUpChallengeRepository.save(verified);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = List.of(
                () -> stepUpChallengeRepository.tryConsume(verified.stepUpChallengeId(), NOW.plusSeconds(2)),
                () -> stepUpChallengeRepository.tryConsume(verified.stepUpChallengeId(), NOW.plusSeconds(2))
            );
            List<Future<Boolean>> results = pool.invokeAll(attempts);
            long winners = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).count();
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        assertThat(stepUpChallengeRepository.tryConsume(verified.stepUpChallengeId(), NOW.plusSeconds(3))).isFalse();
    }

    @Test
    void stepUpTryConsumeFailsOnceExpired() {
        String userSessionId = seedSession();
        StepUpChallenge verified = StepUpChallenge.request(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-1"),
            userSessionId, new AuthorizationTarget("approval:decide", "approval", "ap-1"), "AAL2", List.of("otp"), 3, "corr-1", NOW, NOW.plusSeconds(300)
        ).dispatch("nonce-hash-" + UUID.randomUUID(), NOW).verify("proof-hash-" + UUID.randomUUID(), NOW.plusSeconds(1));
        stepUpChallengeRepository.save(verified);

        assertThat(stepUpChallengeRepository.tryConsume(verified.stepUpChallengeId(), NOW.plusSeconds(301))).isFalse();
    }

    @Test
    void auditRecordsPersistAndAreQueryableByCorrelationId() {
        String correlationId = "corr-" + UUID.randomUUID();
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), IdentityAuditAction.USER_IDENTITY_LINKED, "actor-1",
            "subject-1", null, AuditOutcome.SUCCESS, null, new CorrelationId(correlationId), NOW
        ));

        List<IdentityAuditRecord> found = auditPort.findByCorrelationId(correlationId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).action()).isEqualTo(IdentityAuditAction.USER_IDENTITY_LINKED);
    }

    /** SPEC-UA-031 (07-data-model §identity_audit_records): real {@code previous_hash}/{@code record_hash} chaining, per tenant. */
    @Test
    void auditRecordsChainOntoTheirTenantsMostRecentHashOnPersist() {
        TenantId tenantId = new TenantId("tenant-" + UUID.randomUUID());
        String correlationId = "corr-" + UUID.randomUUID();

        IdentityAuditRecord first = auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), tenantId, IdentityAuditAction.USER_IDENTITY_LINKED, "actor-1",
            "subject-1", null, AuditOutcome.SUCCESS, null, new CorrelationId(correlationId), NOW
        ));
        assertThat(first.previousHash()).isNull();
        assertThat(first.recordHash()).isNotBlank();

        IdentityAuditRecord second = auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), tenantId, IdentityAuditAction.USER_IDENTITY_LINKED, "actor-1",
            "subject-1", null, AuditOutcome.SUCCESS, null, new CorrelationId(correlationId), NOW.plusSeconds(1)
        ));
        assertThat(second.previousHash()).isEqualTo(first.recordHash());
        assertThat(second.recordHash()).isNotBlank().isNotEqualTo(first.recordHash());

        assertThat(auditPort.findMostRecentRecordHash(tenantId)).contains(second.recordHash());

        List<IdentityAuditRecord> rehydrated = auditPort.findByCorrelationId(correlationId);
        assertThat(rehydrated).extracting(IdentityAuditRecord::recordHash).containsExactlyInAnyOrder(first.recordHash(), second.recordHash());
    }

    /** A second tenant's own chain never links onto a different tenant's records. */
    @Test
    void auditRecordChainsAreIsolatedPerTenant() {
        TenantId otherTenant = new TenantId("tenant-" + UUID.randomUUID());
        assertThat(auditPort.findMostRecentRecordHash(otherTenant)).isEmpty();

        IdentityAuditRecord onlyRecordForThisTenant = auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), otherTenant, IdentityAuditAction.USER_IDENTITY_LINKED, "actor-1",
            "subject-1", null, AuditOutcome.SUCCESS, null, new CorrelationId("corr-" + UUID.randomUUID()), NOW
        ));
        assertThat(onlyRecordForThisTenant.previousHash()).isNull();
    }

    /** SPEC-UA-033 (10-failure-handling: "Delayed revocation event | ... | Reconciliation scan"). */
    @Test
    void findAllActiveReturnsOnlyCurrentlyActiveSessions() {
        UserSession active = UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-" + UUID.randomUUID()),
            "idp-hash", "token-hash-" + UUID.randomUUID(), "client-1", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW),
            "device-hash", NOW, NOW.plusSeconds(3600)
        );
        userSessionRepository.save(active);

        UserSession revoked = UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-" + UUID.randomUUID()),
            "idp-hash", "token-hash-" + UUID.randomUUID(), "client-1", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW),
            "device-hash", NOW, NOW.plusSeconds(3600)
        ).revoke("admin-1", "logout", NOW.plusSeconds(1));
        userSessionRepository.save(revoked);

        List<UserSession> allActive = userSessionRepository.findAllActive();
        assertThat(allActive).extracting(UserSession::userSessionId).contains(active.userSessionId());
        assertThat(allActive).extracting(UserSession::userSessionId).doesNotContain(revoked.userSessionId());
    }

    @Test
    void outboxEventsAppendAndDispatchInOrder() {
        outboxEventRepository.append(new OutboxEventRecord(
            UUID.randomUUID().toString(), "UserIdentity", "user-1", "identity.user.provisioned.v1", "v1", "{}",
            "corr-1", OutboxEventStatus.PENDING, 0, NOW, null, NOW
        ));

        List<OutboxEventRecord> due = outboxEventRepository.findPendingBatch(NOW.plusSeconds(1), 10);
        assertThat(due).isNotEmpty();

        outboxEventRepository.markPublished(due.get(0).outboxId(), NOW.plusSeconds(2));
        assertThat(outboxEventRepository.findPendingBatch(NOW.plusSeconds(3), 10))
            .noneMatch(r -> r.outboxId().equals(due.get(0).outboxId()));
    }

    private String seedUser() {
        UserIdentity user = UserIdentity.link(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-" + UUID.randomUUID()),
            "alice", "Alice", null, IdentityType.HUMAN, NOW
        );
        userIdentityRepository.save(user);
        return user.userIdentityId();
    }

    private String seedSession() {
        UserSession session = UserSession.start(
            UUID.randomUUID().toString(), new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "sub-1"), "idp-hash",
            "token-hash-" + UUID.randomUUID(), "client-1", new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW), "device-hash",
            NOW, NOW.plusSeconds(3600)
        );
        userSessionRepository.save(session);
        return session.userSessionId();
    }
}
