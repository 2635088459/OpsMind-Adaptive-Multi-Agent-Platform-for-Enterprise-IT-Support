package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.CancelApprovalCommand;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyCancelledException;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyDecidedException;
import com.opsmind.policygovernance.application.exception.ApprovalNotAuthorizedException;
import com.opsmind.policygovernance.application.exception.ApprovalRequestNotFoundException;
import com.opsmind.policygovernance.application.exception.DuplicateApprovalRequestException;
import com.opsmind.policygovernance.application.exception.InvalidOverrideRequestException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyRevokedException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyUsedException;
import com.opsmind.policygovernance.application.command.RevokeOverrideCommand;
import com.opsmind.policygovernance.application.command.UseOverrideCommand;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.domain.approval.ApprovalCancelledEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestMismatchException;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.approval.NotAnOverrideRequestException;
import com.opsmind.policygovernance.domain.approval.OverrideExpiredException;
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

    /** SPEC-PG-016: every other fixture is RiskLevel.HIGH — this is the one call site that varies it, for the CRITICAL step-up gate. */
    private RequestApprovalCommand requestCommandWithRiskLevel(String requestKey, RiskLevel riskLevel) {
        return new RequestApprovalCommand(
            requestKey, "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, riskLevel, List.of(), Instant.now().plusSeconds(3600), "corr-1", null
        );
    }

    /**
     * SPEC-PG-023 (06-event-contracts §{@code ticket.approval.required.v1}):
     * a ticket-originated request for one of the three named exception
     * sub-types. Unlike {@code POLICY_OVERRIDE}, none of these require
     * expiresAt/constraints — nothing in this spec names that requirement
     * for them, only for override (UC-PG-006).
     */
    private RequestApprovalCommand ticketExceptionRequestCommand(String requestKey, ApprovalType approvalType) {
        return new RequestApprovalCommand(
            requestKey, "hash-ticket", "ticket-workflow", "src-req-ticket", "ticket-1", null, null, null, null,
            "requester-1", approvalType, RiskLevel.HIGH, List.of(), Instant.now().plusSeconds(3600), "corr-1", null
        );
    }

    /** SPEC-PG-022: a valid POLICY_OVERRIDE request command — non-null expiresAt and a non-empty constraint list, both required by UC-PG-006. */
    private RequestApprovalCommand overrideRequestCommand(String requestKey) {
        return new RequestApprovalCommand(
            requestKey, "hash-override", "tool-gateway", "src-req-override", null, null, null, null, null,
            "requester-1", ApprovalType.POLICY_OVERRIDE, RiskLevel.CRITICAL,
            List.of(new com.opsmind.policygovernance.domain.decision.Constraint(
                com.opsmind.policygovernance.domain.decision.Constraint.Type.TIME_WINDOW, "read-only for 1 hour"
            )),
            Instant.now().plusSeconds(3600), "corr-1", null
        );
    }

    /** Grants an override request via the same {@code approver-1} the rest of this test class already uses. */
    private ApprovalRequest approvedOverride(ApprovalService service, String requestKey) {
        ApprovalRequest requested = service.request(overrideRequestCommand(requestKey));
        return service.grant(decideCommandWithAuthenticity(requested, "approver-1", "session-1", "device-1", true));
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
     * SPEC-PG-016 (11-security §Approval Authenticity: "optional MFA/step-up
     * marker"). A {@code CRITICAL}-risk grant without a verified step-up
     * marker is rejected — the narrowest reading of the marker as a real
     * security gate.
     */
    @Test
    void criticalRiskGrantWithoutAVerifiedStepUpMarkerIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommandWithRiskLevel("rk-critical-1", RiskLevel.CRITICAL));

        assertThatThrownBy(() -> service.grant(decideCommandWithAuthenticity(request, "approver-1", null, null, false)))
            .isInstanceOf(ApprovalNotAuthorizedException.class);
    }

    @Test
    void criticalRiskGrantWithAVerifiedStepUpMarkerSucceeds() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommandWithRiskLevel("rk-critical-2", RiskLevel.CRITICAL));

        ApprovalRequest granted = service.grant(decideCommandWithAuthenticity(request, "approver-1", "session-1", "device-1", true));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /** Denying withholds authority rather than granting it — a step-up marker is never required to deny. */
    @Test
    void criticalRiskDenialNeverRequiresAStepUpMarker() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommandWithRiskLevel("rk-critical-3", RiskLevel.CRITICAL));

        ApprovalRequest denied = service.deny(decideCommandWithAuthenticity(request, "approver-1", null, null, false));

        assertThat(denied.status()).isEqualTo(ApprovalStatus.DENIED);
    }

    /** Only CRITICAL risk is gated — 11-security names no broader threshold. */
    @Test
    void nonCriticalRiskGrantDoesNotRequireAStepUpMarker() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommandWithRiskLevel("rk-high-step-up", RiskLevel.HIGH));

        ApprovalRequest granted = service.grant(decideCommandWithAuthenticity(request, "approver-1", null, null, false));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    /** SPEC-PG-016: session/device metadata is recorded on the ApprovalDecision for the audit trail, not just accepted and discarded. */
    @Test
    void sessionAndDeviceMetadataArePersistedOnTheApprovalDecision() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-session-device"));

        service.grant(decideCommandWithAuthenticity(request, "approver-1", "session-42", "device-42", false));

        var persisted = decisionRepository.findByApprovalRequestId(request.approvalRequestId()).orElseThrow();
        assertThat(persisted.sessionId()).isEqualTo("session-42");
        assertThat(persisted.deviceId()).isEqualTo("device-42");
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
        // SPEC-PG-029: the command's own causationId ("cause-1" — decideCommand's fixture value)
        // must reach the published event's envelope, not be silently dropped as null.
        assertThat(staged.payloadJson()).contains("\"causationId\":\"cause-1\"");
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
            request.approvalRequestId(), request.sourceRequestId(), "wrong-hash", "approver-1", "reason", List.of(), "corr-1", "cik-1",
            null, null, false, null
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
            "corr-1", "cik-1", null
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
        // SPEC-PG-029: cancelCommand's own fixture causationId ("cause-1") must reach the envelope.
        assertThat(staged.payloadJson()).contains("\"causationId\":\"cause-1\"");
    }

    private CancelApprovalCommand cancelCommand(ApprovalRequest request, String commandIdempotencyKey) {
        return new CancelApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), "requester-1",
            "no longer needed", "corr-1", commandIdempotencyKey, "cause-1"
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
            "approver-1", "reason", List.of(), "corr-1", "cik-second", null, null, false, null
        );

        assertThatThrownBy(() -> service.grant(secondAttempt)).isInstanceOf(ApprovalAlreadyDecidedException.class);
    }

    private DecideApprovalCommand decideCommand(ApprovalRequest request, String decidedBy) {
        return decideCommand(request, decidedBy, "cik-1");
    }

    private DecideApprovalCommand decideCommand(ApprovalRequest request, String decidedBy, String commandIdempotencyKey) {
        return new DecideApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), decidedBy, "reason", List.of(),
            "corr-1", commandIdempotencyKey, null, null, false, "cause-1"
        );
    }

    /** SPEC-PG-016: same as {@link #decideCommand(ApprovalRequest, String)}, but with session/device/step-up metadata. */
    private DecideApprovalCommand decideCommandWithAuthenticity(
        ApprovalRequest request, String decidedBy, String sessionId, String deviceId, boolean stepUpVerified
    ) {
        return new DecideApprovalCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), decidedBy, "reason", List.of(),
            "corr-1", "cik-1", sessionId, deviceId, stepUpVerified, "cause-1"
        );
    }

    /**
     * SPEC-PG-023 (06-event-contracts §{@code ticket.approval.required.v1}):
     * an SLA-exception approval flows through the exact same request/grant
     * machinery every other approval type already uses — no new endpoint,
     * no new state machine, only a distinct classification.
     */
    @Test
    void aTicketSlaExceptionRequestCanBeRequestedAndGranted() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest requested = service.request(ticketExceptionRequestCommand("rk-ticket-sla", ApprovalType.TICKET_SLA_EXCEPTION));
        assertThat(requested.approvalType()).isEqualTo(ApprovalType.TICKET_SLA_EXCEPTION);

        ApprovalRequest granted = service.grant(decideCommand(requested, "approver-1"));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(granted.approvalType()).isEqualTo(ApprovalType.TICKET_SLA_EXCEPTION);
    }

    /** SPEC-PG-023: same as {@link #aTicketSlaExceptionRequestCanBeRequestedAndGranted}, for TICKET_CLOSURE_OVERRIDE. */
    @Test
    void aTicketClosureOverrideRequestCanBeRequestedAndGranted() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest requested = service.request(ticketExceptionRequestCommand("rk-ticket-closure", ApprovalType.TICKET_CLOSURE_OVERRIDE));

        ApprovalRequest granted = service.grant(decideCommand(requested, "approver-1"));

        assertThat(granted.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(granted.approvalType()).isEqualTo(ApprovalType.TICKET_CLOSURE_OVERRIDE);
    }

    /** SPEC-PG-023: same as {@link #aTicketSlaExceptionRequestCanBeRequestedAndGranted}, for TICKET_ESCALATION_EXCEPTION. */
    @Test
    void aTicketEscalationExceptionRequestCanBeRequestedAndDenied() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest requested = service.request(ticketExceptionRequestCommand("rk-ticket-escalation", ApprovalType.TICKET_ESCALATION_EXCEPTION));

        ApprovalRequest denied = service.deny(decideCommand(requested, "approver-1"));

        assertThat(denied.status()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(denied.approvalType()).isEqualTo(ApprovalType.TICKET_ESCALATION_EXCEPTION);
    }

    /** None of the three ticket exception types are POLICY_OVERRIDE — use()/revoke() must still reject them, the same as any ordinary approval type. */
    @Test
    void ticketExceptionTypesAreNotOverridesAndCannotBeUsedOrRevoked() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest requested = service.request(ticketExceptionRequestCommand("rk-ticket-not-override", ApprovalType.TICKET_SLA_EXCEPTION));
        ApprovalRequest granted = service.grant(decideCommand(requested, "approver-1"));

        assertThatThrownBy(() -> service.use(useCommand(granted, "use-cik-1")))
            .isInstanceOf(NotAnOverrideRequestException.class);
    }

    /**
     * SPEC-PG-024 (11-security §Separation Of Duties: "forbid ... admin
     * repair initiator approving the high-risk override directly"). Whoever
     * calls {@code POST /api/v1/approval-requests} to initiate a
     * POLICY_OVERRIDE (an admin performing a repair, same as any other
     * requester) becomes its {@code requestedBy} — the pre-existing generic
     * self-approval guard {@link #requesterCannotApproveTheirOwnRequest}
     * already covers every approval type, so this rule was already true by
     * construction; this test only confirms it explicitly for the override
     * path this spec's own text names.
     */
    @Test
    void theAdminWhoInitiatesAnOverrideRepairCannotApproveItThemselves() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        RequestApprovalCommand initiatedByAdmin = new RequestApprovalCommand(
            "rk-override-repair", "hash-override-repair", "governance-admin", "src-req-repair", null, null, null, null, null,
            "admin-1", ApprovalType.POLICY_OVERRIDE, RiskLevel.CRITICAL,
            List.of(new com.opsmind.policygovernance.domain.decision.Constraint(
                com.opsmind.policygovernance.domain.decision.Constraint.Type.TIME_WINDOW, "emergency repair window"
            )),
            Instant.now().plusSeconds(3600), "corr-1", null
        );
        ApprovalRequest requested = service.request(initiatedByAdmin);

        assertThatThrownBy(() -> service.grant(decideCommand(requested, "admin-1")))
            .isInstanceOf(com.opsmind.policygovernance.application.exception.ApprovalNotAuthorizedException.class);
    }

    /** SPEC-PG-022 (UC-PG-006): a POLICY_OVERRIDE request without expiresAt is rejected at creation, not silently accepted. */
    @Test
    void requestingAnOverrideWithoutExpiresAtIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        RequestApprovalCommand withoutExpiry = new RequestApprovalCommand(
            "rk-override-no-expiry", "hash-override", "tool-gateway", "src-req-override", null, null, null, null, null,
            "requester-1", ApprovalType.POLICY_OVERRIDE, RiskLevel.CRITICAL,
            List.of(new com.opsmind.policygovernance.domain.decision.Constraint(
                com.opsmind.policygovernance.domain.decision.Constraint.Type.TIME_WINDOW, "read-only for 1 hour"
            )),
            null, "corr-1", null
        );

        assertThatThrownBy(() -> service.request(withoutExpiry)).isInstanceOf(InvalidOverrideRequestException.class);
    }

    /** SPEC-PG-022 (UC-PG-006): a POLICY_OVERRIDE request without any constraint (its scope) is rejected at creation. */
    @Test
    void requestingAnOverrideWithoutAScopeConstraintIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        RequestApprovalCommand withoutScope = new RequestApprovalCommand(
            "rk-override-no-scope", "hash-override", "tool-gateway", "src-req-override", null, null, null, null, null,
            "requester-1", ApprovalType.POLICY_OVERRIDE, RiskLevel.CRITICAL, List.of(),
            Instant.now().plusSeconds(3600), "corr-1", null
        );

        assertThatThrownBy(() -> service.request(withoutScope)).isInstanceOf(InvalidOverrideRequestException.class);
    }

    /** A non-override request never needs expiresAt/constraints — the override-only validation must not leak onto other approval types. */
    @Test
    void nonOverrideRequestsAreUnaffectedByTheOverrideValidation() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());

        ApprovalRequest saved = service.request(requestCommand("rk-not-an-override"));

        assertThat(saved.status()).isEqualTo(ApprovalStatus.REQUESTED);
    }

    /** SPEC-PG-022: use() transitions an approved override to USED and writes the OVERRIDE_APPLIED audit fact. */
    @Test
    void useTransitionsAnApprovedOverrideToUsed() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-use");

        ApprovalRequest used = service.use(useCommand(approved, "use-cik-1"));

        assertThat(used.status()).isEqualTo(ApprovalStatus.USED);
    }

    /** Mirrors {@link #retryingTheIdenticalCancelIsIdempotent}: a retried use command with the same key must not throw. */
    @Test
    void retryingTheIdenticalUseIsIdempotent() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-use-retry");
        UseOverrideCommand command = useCommand(approved, "use-cik-1");

        ApprovalRequest first = service.use(command);
        ApprovalRequest second = service.use(command);

        assertThat(second.status()).isEqualTo(ApprovalStatus.USED);
        assertThat(second.approvalRequestId()).isEqualTo(first.approvalRequestId());
    }

    /** Mirrors {@link #aDifferentCancelAttemptAfterAFinalCancelIsAConflict}. */
    @Test
    void aDifferentUseAttemptAfterAnAlreadyUsedOverrideIsAConflict() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-use-conflict");
        service.use(useCommand(approved, "use-cik-first"));

        assertThatThrownBy(() -> service.use(useCommand(approved, "use-cik-second")))
            .isInstanceOf(OverrideAlreadyUsedException.class);
    }

    /** use() is rejected outright for a non-POLICY_OVERRIDE approval type — no OVERRIDE_USED continuation exists for it. */
    @Test
    void useIsRejectedForANonOverrideApprovalType() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-not-an-override-use"));
        ApprovalRequest granted = service.grant(decideCommand(request, "approver-1"));

        assertThatThrownBy(() -> service.use(useCommand(granted, "use-cik-1")))
            .isInstanceOf(NotAnOverrideRequestException.class);
    }

    /** UC-PG-006: use() enforces the override's own time window, not just its scope. */
    @Test
    void useIsRejectedOncePastTheOverrideExpiresAt() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        RequestApprovalCommand alreadyLapsed = new RequestApprovalCommand(
            "rk-override-lapsed", "hash-override", "tool-gateway", "src-req-override", null, null, null, null, null,
            "requester-1", ApprovalType.POLICY_OVERRIDE, RiskLevel.CRITICAL,
            List.of(new com.opsmind.policygovernance.domain.decision.Constraint(
                com.opsmind.policygovernance.domain.decision.Constraint.Type.TIME_WINDOW, "read-only for 1 hour"
            )),
            Instant.now().plusSeconds(1), "corr-1", null
        );
        ApprovalRequest requested = service.request(alreadyLapsed);
        ApprovalRequest approved = service.grant(decideCommandWithAuthenticity(requested, "approver-1", "session-1", "device-1", true));

        ApprovalService lateService = new ApprovalService(
            requestRepository, decisionRepository, new FakeIdentityAuthorizationPort(), new NoOpApprovalNotificationAdapter(),
            auditService, new NoOpGovernanceMetrics(), Clock.fixed(Instant.now().plusSeconds(3600), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> lateService.use(useCommand(approved, "use-cik-1")))
            .isInstanceOf(OverrideExpiredException.class);
    }

    /** SPEC-PG-022: revoke() transitions an approved override to REVOKED and writes the OVERRIDE_REVOKED audit fact. */
    @Test
    void revokeTransitionsAnApprovedOverrideToRevoked() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-revoke");

        ApprovalRequest revoked = service.revoke(revokeCommand(approved, "revoke-cik-1"));

        assertThat(revoked.status()).isEqualTo(ApprovalStatus.REVOKED);
    }

    /** Mirrors {@link #retryingTheIdenticalUseIsIdempotent}. */
    @Test
    void retryingTheIdenticalRevokeIsIdempotent() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-revoke-retry");
        RevokeOverrideCommand command = revokeCommand(approved, "revoke-cik-1");

        ApprovalRequest first = service.revoke(command);
        ApprovalRequest second = service.revoke(command);

        assertThat(second.status()).isEqualTo(ApprovalStatus.REVOKED);
        assertThat(second.approvalRequestId()).isEqualTo(first.approvalRequestId());
    }

    /** Mirrors {@link #aDifferentUseAttemptAfterAnAlreadyUsedOverrideIsAConflict}. */
    @Test
    void aDifferentRevokeAttemptAfterAnAlreadyRevokedOverrideIsAConflict() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-revoke-conflict");
        service.revoke(revokeCommand(approved, "revoke-cik-first"));

        assertThatThrownBy(() -> service.revoke(revokeCommand(approved, "revoke-cik-second")))
            .isInstanceOf(OverrideAlreadyRevokedException.class);
    }

    /** revoke() is rejected outright for a non-POLICY_OVERRIDE approval type, mirroring {@link #useIsRejectedForANonOverrideApprovalType}. */
    @Test
    void revokeIsRejectedForANonOverrideApprovalType() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest request = service.request(requestCommand("rk-not-an-override-revoke"));
        ApprovalRequest granted = service.grant(decideCommand(request, "approver-1"));

        assertThatThrownBy(() -> service.revoke(revokeCommand(granted, "revoke-cik-1")))
            .isInstanceOf(NotAnOverrideRequestException.class);
    }

    /** INV-PG-005: use/revoke re-validate request linkage the same way grant/deny/cancel do. */
    @Test
    void usingWithAMismatchedRequestHashIsRejected() {
        ApprovalService service = serviceWith(new FakeIdentityAuthorizationPort());
        ApprovalRequest approved = approvedOverride(service, "rk-override-use-mismatch");

        UseOverrideCommand mismatched = new UseOverrideCommand(
            approved.approvalRequestId(), approved.sourceRequestId(), "wrong-hash", "user-1", "exercising the override",
            "corr-1", "use-cik-1", null
        );

        assertThatThrownBy(() -> service.use(mismatched)).isInstanceOf(ApprovalRequestMismatchException.class);
    }

    private UseOverrideCommand useCommand(ApprovalRequest request, String commandIdempotencyKey) {
        return new UseOverrideCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), "user-1",
            "exercising the override", "corr-1", commandIdempotencyKey, "cause-1"
        );
    }

    private RevokeOverrideCommand revokeCommand(ApprovalRequest request, String commandIdempotencyKey) {
        return new RevokeOverrideCommand(
            request.approvalRequestId(), request.sourceRequestId(), request.requestHash(), "governance-1",
            "circumstances changed", "corr-1", commandIdempotencyKey, "cause-1"
        );
    }
}
