package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.CancelApprovalCommand;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyCancelledException;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyDecidedException;
import com.opsmind.policygovernance.application.exception.ApprovalNotAuthorizedException;
import com.opsmind.policygovernance.application.exception.ApprovalRequestNotFoundException;
import com.opsmind.policygovernance.application.exception.DuplicateApprovalRequestException;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.domain.approval.ApprovalCancelledEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestMismatchException;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.infrastructure.identity.JwtIdentityAuthorizationAdapter;
import com.opsmind.policygovernance.infrastructure.notification.NoOpApprovalNotificationAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryApprovalDecisionRepository;
import com.opsmind.policygovernance.support.InMemoryApprovalRequestRepository;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.FakeIdentityAuthorizationPort;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ApprovalServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryApprovalRequestRepository requestRepository = new InMemoryApprovalRequestRepository();
    private final InMemoryApprovalDecisionRepository decisionRepository = new InMemoryApprovalDecisionRepository();
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock), clock
    );

    private ApprovalService serviceWith(com.opsmind.policygovernance.application.port.IdentityAuthorizationPort identity) {
        return new ApprovalService(
            requestRepository, decisionRepository, identity, new NoOpApprovalNotificationAdapter(),
            auditService, new NoOpGovernanceMetrics(), clock
        );
    }

    private RequestApprovalCommand requestCommand(String requestKey) {
        return new RequestApprovalCommand(
            requestKey, "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), "corr-1", null
        );
    }

    /** SPEC-PG-015: {@code executorId} is nullable on every other fixture — this is the one call site that supplies it. */
    private RequestApprovalCommand requestCommandWithExecutor(String requestKey, String executorId) {
        return new RequestApprovalCommand(
            requestKey, "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", executorId, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), "corr-1", null
        );
    }

    @Test
    void requestIsIdempotentOnRequestKey() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest first = service.request(requestCommand("rk-1"));
        ApprovalRequest second = service.request(requestCommand("rk-1"));

        assertThat(second.approvalRequestId()).isEqualTo(first.approvalRequestId());
    }

    /**
     * SPEC-PG-010: {@code request()} must stage the real {@code
     * approval.requested.v1} event (06-event-contracts), not the generic
     * {@code governance.audit.approval_requested.v1} placeholder — and the
     * outbox row's own {@code aggregateType}/{@code aggregateId} columns
     * must identify the ApprovalRequest itself, not a self-referencing id.
     */
    @Test
    void requestStagesTheRealApprovalRequestedEventWithCorrectAggregateIdentity() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());

        ApprovalRequest saved = service.request(requestCommand("rk-event"));

        OutboxEventRecord staged = outboxEventRepository.all().stream()
            .filter(r -> r.eventType().equals(ApprovalRequestedEvent.EVENT_TYPE))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no approval.requested.v1 row was staged"));
        assertThat(staged.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(staged.aggregateId()).isEqualTo(saved.approvalRequestId());
        assertThat(staged.payloadJson()).contains("\"approvalRequestId\":\"" + saved.approvalRequestId() + "\"");
    }

    @Test
    void findByIdReturnsThePersistedRequest() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest saved = service.request(requestCommand("rk-find"));

        ApprovalRequest found = service.findById(saved.approvalRequestId());

        assertThat(found).isEqualTo(saved);
    }

    @Test
    void findByIdThrowsWhenNoSuchRequestExists() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());

        assertThatThrownBy(() -> service.findById("does-not-exist"))
            .isInstanceOf(ApprovalRequestNotFoundException.class);
    }

    @Test
    void reusingARequestKeyWithADifferentPayloadIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        service.request(requestCommand("rk-2"));

        RequestApprovalCommand conflicting = new RequestApprovalCommand(
            "rk-2", "different-hash", "tool-gateway", "src-req-1", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), "corr-1", null
        );

        assertThatThrownBy(() -> service.request(conflicting)).isInstanceOf(DuplicateApprovalRequestException.class);
    }

    /**
     * SPEC-PG-014: the real production adapter still fails closed by
     * default — with no authenticated {@code SecurityContext} at all (the
     * state the JVM starts in outside a real HTTP request), it must deny
     * exactly like the retired fail-closed stub it replaced.
     */
    @Test
    void productionIdentityAdapterFailsClosedOnGrantWithoutAnAuthenticatedSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        try {
            ApprovalService service = serviceWith(new JwtIdentityAuthorizationAdapter());
            ApprovalRequest request = service.request(requestCommand("rk-3"));

            assertThatThrownBy(() -> service.grant(decideCommand(request, "approver-1")))
                .isInstanceOf(ApprovalNotAuthorizedException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void requesterCannotApproveTheirOwnRequest() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-4"));

        assertThatThrownBy(() -> service.grant(decideCommand(request, "requester-1")))
            .isInstanceOf(ApprovalNotAuthorizedException.class);
    }

    /**
     * SPEC-PG-015 (11-security §Separation Of Duties: "forbid ... tool
     * execution worker approving the corresponding tool request").
     */
    @Test
    void theExecutorAssignedToTheToolRequestCannotApproveTheirOwnExecution() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommandWithExecutor("rk-executor-1", "executor-1"));

        assertThatThrownBy(() -> service.grant(decideCommand(request, "executor-1")))
            .isInstanceOf(ApprovalNotAuthorizedException.class);
    }

    /** A different actor than the assigned executor can still decide the request. */
    @Test
    void aDifferentActorThanTheAssignedExecutorCanStillApprove() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommandWithExecutor("rk-executor-2", "executor-1"));

        ApprovalRequest granted = service.grant(decideCommand(request, "approver-1"));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /** No executorId was ever supplied — the separation-of-duties check for it is a no-op, not a false denial. */
    @Test
    void approvalWithNoExecutorIdIsUnaffectedByTheExecutorSeparationOfDutiesCheck() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-no-executor"));

        ApprovalRequest granted = service.grant(decideCommand(request, "approver-1"));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void grantingWithAnAuthorizedIndependentApproverSucceeds() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-5"));

        ApprovalRequest granted = service.grant(decideCommand(request, "approver-1"));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /**
     * SPEC-PG-013: {@code grant()} must stage the real {@code
     * approval.granted.v1} event, not the generic {@code
     * governance.audit.approval_granted.v1} placeholder — mirroring {@link
     * #requestStagesTheRealApprovalRequestedEventWithCorrectAggregateIdentity}.
     */
    @Test
    void grantStagesTheRealApprovalGrantedEventWithCorrectAggregateIdentity() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-grant-event"));

        ApprovalRequest granted = service.grant(decideCommand(request, "approver-1"));

        OutboxEventRecord staged = outboxEventRepository.all().stream()
            .filter(r -> r.eventType().equals(com.opsmind.policygovernance.domain.approval.ApprovalGrantedEvent.EVENT_TYPE))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no approval.granted.v1 row was staged"));
        assertThat(staged.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(staged.aggregateId()).isEqualTo(granted.approvalRequestId());
        assertThat(staged.payloadJson()).contains("\"approvalRequestId\":\"" + granted.approvalRequestId() + "\"");
    }

    /** SPEC-PG-013: same as above, for {@code deny()} and {@code approval.denied.v1}. */
    @Test
    void denyStagesTheRealApprovalDeniedEventWithCorrectAggregateIdentity() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-deny-event"));

        ApprovalRequest denied = service.deny(decideCommand(request, "approver-1"));

        OutboxEventRecord staged = outboxEventRepository.all().stream()
            .filter(r -> r.eventType().equals(com.opsmind.policygovernance.domain.approval.ApprovalDeniedEvent.EVENT_TYPE))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no approval.denied.v1 row was staged"));
        assertThat(staged.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(staged.aggregateId()).isEqualTo(denied.approvalRequestId());
        assertThat(staged.payloadJson()).contains("\"approvalRequestId\":\"" + denied.approvalRequestId() + "\"");
    }

    @Test
    void decidingWithAMismatchedRequestHashIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-6"));

        DecideApprovalCommand mismatched = new DecideApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), "wrong-hash", "approver-1", "reason", List.of(), "corr-1", "cik-1"
        );

        assertThatThrownBy(() -> service.grant(mismatched)).isInstanceOf(ApprovalRequestMismatchException.class);
    }

    @Test
    void cancelIsIrreversible() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-7"));

        ApprovalRequest cancelled = service.cancel(cancelCommand(request, "cik-cancel-1"));

        assertThat(cancelled.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThatThrownBy(() -> service.grant(decideCommand(cancelled, "approver-1")))
            .isInstanceOf(com.opsmind.policygovernance.domain.approval.IllegalApprovalTransitionException.class);
    }

    /** SPEC-PG-011 named this exact gap as out of its own scope: a retried cancel command must not throw. */
    @Test
    void retryingTheIdenticalCancelIsIdempotent() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-cancel-retry"));
        CancelApprovalCommand command = cancelCommand(request, "cik-cancel-1");

        ApprovalRequest first = service.cancel(command);
        ApprovalRequest second = service.cancel(command);

        assertThat(second.status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(second.approvalRequestId()).isEqualTo(first.approvalRequestId());
    }

    /** A different commandIdempotencyKey against an already-cancelled request is a genuine conflict, not a replay. */
    @Test
    void aDifferentCancelAttemptAfterAFinalCancelIsAConflict() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-cancel-conflict"));
        service.cancel(cancelCommand(request, "cik-first"));

        assertThatThrownBy(() -> service.cancel(cancelCommand(request, "cik-second")))
            .isInstanceOf(ApprovalAlreadyCancelledException.class);
    }

    /** INV-PG-005: a cancel command must target the exact sourceRequestId/requestHash the request was opened with. */
    @Test
    void cancellingWithAMismatchedRequestHashIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-cancel-mismatch"));

        CancelApprovalCommand mismatched = new CancelApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), "wrong-hash", "requester-1", "no longer needed",
            "corr-1", "cik-1"
        );

        assertThatThrownBy(() -> service.cancel(mismatched)).isInstanceOf(ApprovalRequestMismatchException.class);
    }

    /**
     * SPEC-PG-012: {@code cancel()} must stage the real {@code
     * approval.cancelled.v1} event, not the generic {@code
     * governance.audit.approval_cancelled.v1} placeholder — mirroring
     * {@link #requestStagesTheRealApprovalRequestedEventWithCorrectAggregateIdentity}.
     */
    @Test
    void cancelStagesTheRealApprovalCancelledEventWithCorrectAggregateIdentity() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-cancel-event"));

        ApprovalRequest cancelled = service.cancel(cancelCommand(request, "cik-1"));

        OutboxEventRecord staged = outboxEventRepository.all().stream()
            .filter(r -> r.eventType().equals(ApprovalCancelledEvent.EVENT_TYPE))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no approval.cancelled.v1 row was staged"));
        assertThat(staged.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(staged.aggregateId()).isEqualTo(cancelled.approvalRequestId());
        assertThat(staged.payloadJson()).contains("\"approvalRequestId\":\"" + cancelled.approvalRequestId() + "\"");
    }

    private CancelApprovalCommand cancelCommand(ApprovalRequest request, String commandIdempotencyKey) {
        return new CancelApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), "requester-1",
            "no longer needed", "corr-1", commandIdempotencyKey
        );
    }

    @Test
    void retryingTheIdenticalGrantIsIdempotent() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-8"));
        DecideApprovalCommand command = decideCommand(request, "approver-1");

        ApprovalRequest first = service.grant(command);
        ApprovalRequest second = service.grant(command);

        assertThat(second.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(second.approvalRequestId()).isEqualTo(first.approvalRequestId());
    }

    @Test
    void aDifferentApproverRetryingAfterAFinalDecisionIsAConflict() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-9"));
        service.grant(decideCommand(request, "approver-1"));

        assertThatThrownBy(() -> service.deny(decideCommand(request, "approver-2")))
            .isInstanceOf(ApprovalAlreadyDecidedException.class);
    }

    /**
     * SPEC-PG-011: "same attempt" is a strict three-way match on {@code
     * commandIdempotencyKey}/{@code decision}/{@code decidedBy}. Even though
     * outcome and actor agree, a genuinely different command (different
     * key) must still be rejected as a conflict — a coincidental
     * outcome/actor match is not proof it's the same retry.
     */
    @Test
    void sameOutcomeAndActorWithADifferentIdempotencyKeyIsStillAConflict() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-10"));
        service.grant(decideCommand(request, "approver-1", "cik-first"));

        DecideApprovalCommand secondAttempt = new DecideApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(),
            "approver-1", "reason", List.of(), "corr-1", "cik-second"
        );

        assertThatThrownBy(() -> service.grant(secondAttempt)).isInstanceOf(ApprovalAlreadyDecidedException.class);
    }

    private DecideApprovalCommand decideCommand(ApprovalRequest request, String decidedBy) {
        return decideCommand(request, decidedBy, "cik-1");
    }

    private DecideApprovalCommand decideCommand(ApprovalRequest request, String decidedBy, String commandIdempotencyKey) {
        return new DecideApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), decidedBy, "reason", List.of(),
            "corr-1", commandIdempotencyKey
        );
    }
}
