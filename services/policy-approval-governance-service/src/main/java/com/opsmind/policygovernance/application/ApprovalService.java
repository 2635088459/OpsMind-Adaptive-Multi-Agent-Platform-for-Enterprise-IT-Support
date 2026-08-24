package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.command.CancelApprovalCommand;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.application.command.RevokeOverrideCommand;
import com.opsmind.policygovernance.application.command.UseOverrideCommand;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyCancelledException;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyDecidedException;
import com.opsmind.policygovernance.application.exception.ApprovalNotAuthorizedException;
import com.opsmind.policygovernance.application.exception.ApprovalRequestNotFoundException;
import com.opsmind.policygovernance.application.exception.DuplicateApprovalRequestException;
import com.opsmind.policygovernance.application.exception.InvalidOverrideRequestException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyRevokedException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyUsedException;
import com.opsmind.policygovernance.application.port.ApprovalDecisionRepository;
import com.opsmind.policygovernance.application.port.ApprovalNotificationPort;
import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.application.port.GovernanceMetricsPort;
import com.opsmind.policygovernance.application.port.IdentityAuthorizationPort;
import com.opsmind.policygovernance.domain.approval.ApprovalCancelledEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.domain.approval.ApprovalDeniedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalGrantedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestMismatchException;
import com.opsmind.policygovernance.domain.approval.ApprovalRequestedEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.audit.GovernanceAuditRecord;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * UC-PG-002/003/004: create an {@link ApprovalRequest}, then grant, deny, or
 * cancel it. Every decision path re-validates request linkage (INV-PG-005),
 * rejects self-approval and unauthorized approvers (test-plan security
 * tests), and relies on {@link ApprovalDecision}'s own constructor to refuse
 * an APPROVED outcome without a passed separation-of-duties check
 * (INV-PG-004) — so the guard holds even if a caller here had a bug.
 *
 * <p>SPEC-PG-029 (12-observability §Logs, §Metrics): every command here
 * now threads its own {@code causationId} through to the audit record, the
 * staged event, and the structured log — grant/deny/cancel/use/revoke had
 * silently dropped it (hardcoded {@code null}) since each one's own {@code
 * Command} record had no field for it, even though {@code
 * api.support.GovernanceRequestContext#causationId} already read the
 * {@code X-Causation-Id} header generically for every endpoint. Every
 * structured log call also now carries every 12-observability §Logs field
 * that is structurally available at that call site ({@code
 * policyDecisionId}/{@code ticketId}/{@code workflowInstanceId} included,
 * even when {@code null} — a {@code null} value there is itself meaningful:
 * "this request is not ticket/workflow/decision-scoped").
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRequestRepository approvalRequestRepository;
    private final ApprovalDecisionRepository approvalDecisionRepository;
    private final IdentityAuthorizationPort identityAuthorizationPort;
    private final ApprovalNotificationPort notificationPort;
    private final GovernanceAuditService auditService;
    private final GovernanceMetricsPort metrics;
    private final Clock clock;

    public ApprovalService(
        ApprovalRequestRepository approvalRequestRepository,
        ApprovalDecisionRepository approvalDecisionRepository,
        IdentityAuthorizationPort identityAuthorizationPort,
        ApprovalNotificationPort notificationPort,
        GovernanceAuditService auditService,
        GovernanceMetricsPort metrics,
        Clock clock
    ) {
        this.approvalRequestRepository = approvalRequestRepository;
        this.approvalDecisionRepository = approvalDecisionRepository;
        this.identityAuthorizationPort = identityAuthorizationPort;
        this.notificationPort = notificationPort;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public ApprovalRequest request(RequestApprovalCommand command) {
        if (command.approvalType() == ApprovalType.POLICY_OVERRIDE) {
            if (command.expiresAt() == null) {
                throw new InvalidOverrideRequestException("a POLICY_OVERRIDE request must specify expiresAt (UC-PG-006: valid only within a limited time window)");
            }
            if (command.constraints().isEmpty()) {
                throw new InvalidOverrideRequestException("a POLICY_OVERRIDE request must specify at least one constraint (UC-PG-006: valid only within a limited scope)");
            }
        }

        Optional<ApprovalRequest> existing = approvalRequestRepository.findByRequestKey(command.requestKey());
        if (existing.isPresent()) {
            if (!existing.get().matches(command.sourceRequestId(), command.requestHash())) {
                throw new DuplicateApprovalRequestException(command.requestKey());
            }
            return existing.get();
        }

        ApprovalRequest approvalRequest = ApprovalRequest.requested(
            UUID.randomUUID().toString(), command.requestKey(), command.requestHash(),
            command.sourceDomain(), command.sourceRequestId(), command.ticketId(),
            command.workflowInstanceId(), command.toolRequestId(), command.executorId(), command.policyDecisionId(), command.requestedBy(),
            command.approvalType(), command.riskLevel(), command.constraints(),
            command.expiresAt(), clock.instant()
        );
        ApprovalRequest saved = approvalRequestRepository.save(approvalRequest);
        notificationPort.notifyRequested(saved);
        auditService.record(
            GovernanceAuditRecord.Action.APPROVAL_REQUESTED, command.requestedBy(), command.sourceDomain(),
            command.sourceRequestId(), null, null, "approval requested", command.correlationId(), command.causationId(),
            saved.ticketId(), saved.approvalRequestId(), saved.policyDecisionId(),
            ApprovalRequestedEvent.from(saved, command.correlationId(), command.causationId())
        );
        metrics.recordApprovalRequested(command.approvalType(), command.riskLevel());
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("causationId", command.causationId())
            .addKeyValue("approvalRequestId", saved.approvalRequestId())
            .addKeyValue("policyDecisionId", saved.policyDecisionId())
            .addKeyValue("sourceDomain", saved.sourceDomain())
            .addKeyValue("sourceRequestId", saved.sourceRequestId())
            .addKeyValue("ticketId", saved.ticketId())
            .addKeyValue("workflowInstanceId", saved.workflowInstanceId())
            .addKeyValue("riskLevel", saved.riskLevel())
            .log("approval requested");
        return saved;
    }

    @Transactional
    public ApprovalRequest grant(DecideApprovalCommand command) {
        return decide(command, ApprovalDecision.Outcome.APPROVED);
    }

    @Transactional
    public ApprovalRequest deny(DecideApprovalCommand command) {
        return decide(command, ApprovalDecision.Outcome.DENIED);
    }

    /**
     * 08-transaction-and-outbox §Approval Decision: locks the request row
     * first (step 1), then re-checks whether it already has a final
     * decision. 09-concurrency-and-idempotency §Concurrent Approval: "the
     * first transaction that commits a final decision succeeds; later
     * requests return the existing final decision; conflicting payload
     * returns conflict and writes audit."
     *
     * <p>SPEC-PG-011: "same attempt" is now a strict three-way match on
     * {@code commandIdempotencyKey}/{@code decision}/{@code decidedBy}, not
     * outcome+actor alone — the {@link com.opsmind.policygovernance.domain.approval.ApprovalDecision}
     * idempotency key 09-concurrency-and-idempotency names distinguishes a
     * genuine retry of the same command from two different attempts that
     * merely happen to agree on outcome and actor.
     *
     * <p>SPEC-PG-013: stages the real, versioned {@code approval.granted.v1}/
     * {@code approval.denied.v1} event ({@link ApprovalGrantedEvent}/{@link
     * ApprovalDeniedEvent}) instead of the generic outbox placeholder — the
     * last two approval-lifecycle actions still on it after SPEC-PG-010
     * graduated {@code approval.requested.v1} and SPEC-PG-012 graduated
     * {@code approval.expired.v1}/{@code approval.cancelled.v1}.
     *
     * <p>SPEC-PG-015 (11-security §Separation Of Duties: "forbid ... tool
     * execution worker approving the corresponding tool request"): rejects
     * the request's own {@code executorId}, mirroring the pre-existing
     * requester self-approval guard immediately above it — a structural
     * check ahead of {@link com.opsmind.policygovernance.application.port.IdentityAuthorizationPort}'s
     * RBAC/ABAC check, not delegated to it, since the request itself (not
     * an external identity provider) is the only source of truth for who is
     * assigned to execute it.
     *
     * <p>SPEC-PG-016 (11-security §Approval Authenticity, INV-PG-003):
     * records {@code sessionId}/{@code deviceId}/{@code stepUpVerified} on
     * the resulting {@link ApprovalDecision} for the audit trail, and
     * structurally requires {@code stepUpVerified} for a {@code CRITICAL}
     * -risk grant — the narrowest reading of "optional MFA/step-up marker"
     * that makes it a real gate rather than inert data collection. Denials
     * never need step-up (denying withholds authority; it does not grant
     * it), and sub-{@code CRITICAL} risk levels are left ungated since
     * nothing in 11-security names a broader threshold.
     */
    private ApprovalRequest decide(DecideApprovalCommand command, ApprovalDecision.Outcome outcome) {
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(command.approvalRequestId())
            .orElseThrow(() -> new ApprovalRequestNotFoundException(command.approvalRequestId()));

        if (!approvalRequest.matches(command.sourceRequestId(), command.requestHash())) {
            throw new ApprovalRequestMismatchException(command.approvalRequestId());
        }

        Optional<ApprovalDecision> existingDecision = approvalDecisionRepository.findByApprovalRequestId(approvalRequest.approvalRequestId());
        if (existingDecision.isPresent()) {
            ApprovalDecision existing = existingDecision.get();
            boolean sameAttempt = existing.commandIdempotencyKey().equals(command.commandIdempotencyKey())
                && existing.decision() == outcome && existing.decidedBy().equals(command.decidedBy());
            if (sameAttempt) {
                return approvalRequest;
            }
            auditService.record(
                GovernanceAuditRecord.Action.APPROVAL_DECISION_CONFLICT, command.decidedBy(), approvalRequest.sourceDomain(),
                approvalRequest.sourceRequestId(), null, null,
                "conflicting decision rejected: request already has a final decision", command.correlationId(), command.causationId(),
                approvalRequest.ticketId(), approvalRequest.approvalRequestId(), approvalRequest.policyDecisionId()
            );
            throw new ApprovalAlreadyDecidedException(approvalRequest.approvalRequestId());
        }

        if (approvalRequest.requestedBy().equals(command.decidedBy())) {
            throw new ApprovalNotAuthorizedException("requester cannot approve their own request");
        }
        if (approvalRequest.executorId() != null && approvalRequest.executorId().equals(command.decidedBy())) {
            throw new ApprovalNotAuthorizedException("the executor of this tool request cannot approve their own execution");
        }
        if (!identityAuthorizationPort.isAuthorizedApprover(command.decidedBy(), approvalRequest.approvalType(), approvalRequest.riskLevel())) {
            throw new ApprovalNotAuthorizedException("actor is not an authorized approver for this approval type and risk level");
        }
        if (outcome == ApprovalDecision.Outcome.APPROVED && approvalRequest.riskLevel() == RiskLevel.CRITICAL && !command.stepUpVerified()) {
            throw new ApprovalNotAuthorizedException("critical-risk approval requires a verified step-up/MFA marker");
        }

        boolean separationOfDutiesCheck = outcome == ApprovalDecision.Outcome.APPROVED
            && identityAuthorizationPort.isIndependentApprover(approvalRequest.requestedBy(), command.decidedBy());

        Instant now = clock.instant();
        ApprovalDecision decision = new ApprovalDecision(
            UUID.randomUUID().toString(), approvalRequest.approvalRequestId(), outcome,
            command.decidedBy(), now, command.reason(), command.conditions(), separationOfDutiesCheck,
            command.commandIdempotencyKey(), command.sessionId(), command.deviceId(), command.stepUpVerified()
        );
        ApprovalDecision savedDecision = approvalDecisionRepository.save(decision);

        ApprovalRequest updated = outcome == ApprovalDecision.Outcome.APPROVED
            ? approvalRequest.approve(savedDecision, now)
            : approvalRequest.deny(savedDecision, now);
        ApprovalRequest saved = approvalRequestRepository.save(updated);
        notificationPort.notifyDecided(saved);

        auditService.record(
            outcome == ApprovalDecision.Outcome.APPROVED ? GovernanceAuditRecord.Action.APPROVAL_GRANTED : GovernanceAuditRecord.Action.APPROVAL_DENIED,
            command.decidedBy(), approvalRequest.sourceDomain(), approvalRequest.sourceRequestId(),
            null, null, command.reason(), command.correlationId(), command.causationId(),
            saved.ticketId(), saved.approvalRequestId(), saved.policyDecisionId(),
            outcome == ApprovalDecision.Outcome.APPROVED
                ? ApprovalGrantedEvent.from(saved, savedDecision, command.correlationId(), command.causationId())
                : ApprovalDeniedEvent.from(saved, savedDecision, command.correlationId(), command.causationId())
        );

        Duration waitTime = Duration.between(approvalRequest.createdAt(), now);
        metrics.recordApprovalDecision(outcome, approvalRequest.riskLevel(), approvalRequest.approvalType(), waitTime);
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("causationId", command.causationId())
            .addKeyValue("approvalRequestId", saved.approvalRequestId())
            .addKeyValue("policyDecisionId", saved.policyDecisionId())
            .addKeyValue("sourceDomain", saved.sourceDomain())
            .addKeyValue("sourceRequestId", saved.sourceRequestId())
            .addKeyValue("ticketId", saved.ticketId())
            .addKeyValue("workflowInstanceId", saved.workflowInstanceId())
            .addKeyValue("riskLevel", saved.riskLevel())
            .addKeyValue("effect", outcome)
            .log("approval decided");
        return saved;
    }

    /**
     * SPEC-PG-012: locks the request row first, mirroring {@link #decide}'s
     * own reasoning (08-transaction-and-outbox, 09-concurrency-and-idempotency)
     * — a concurrent grant/deny/cancel on the same request must block here
     * instead of racing. Re-validates request linkage (INV-PG-005) the same
     * way and in the same order {@link #decide} does — before any
     * idempotency check, so a malformed retry can never slip through on a
     * coincidentally-matching idempotency key. A request already {@code
     * CANCELLED} is then idempotent on a matching {@code commandIdempotencyKey}
     * (a genuine retry returns the existing final state unchanged); a
     * different key against an already-cancelled request is a conflict,
     * audited and rejected the same way {@link #decide} audits a
     * conflicting grant/deny (SPEC-PG-011 named this exact gap as out of its
     * own scope). Any other non-{@code REQUESTED} status
     * (APPROVED/DENIED/EXPIRED/SUPERSEDED) falls through to {@link
     * ApprovalRequest#cancel}, which throws {@code
     * IllegalApprovalTransitionException} — final states stay irreversible.
     */
    @Transactional
    public ApprovalRequest cancel(CancelApprovalCommand command) {
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(command.approvalRequestId())
            .orElseThrow(() -> new ApprovalRequestNotFoundException(command.approvalRequestId()));

        if (!approvalRequest.matches(command.sourceRequestId(), command.requestHash())) {
            throw new ApprovalRequestMismatchException(approvalRequest.approvalRequestId());
        }

        if (approvalRequest.status() == ApprovalStatus.CANCELLED) {
            if (command.commandIdempotencyKey().equals(approvalRequest.cancelCommandIdempotencyKey())) {
                return approvalRequest;
            }
            auditService.record(
                GovernanceAuditRecord.Action.APPROVAL_CANCEL_CONFLICT, command.cancelledBy(), approvalRequest.sourceDomain(),
                approvalRequest.sourceRequestId(), null, null,
                "conflicting cancel rejected: request already cancelled by a different attempt", command.correlationId(), command.causationId(),
                approvalRequest.ticketId(), approvalRequest.approvalRequestId(), approvalRequest.policyDecisionId()
            );
            throw new ApprovalAlreadyCancelledException(approvalRequest.approvalRequestId());
        }

        Instant now = clock.instant();
        ApprovalRequest saved = approvalRequestRepository.save(approvalRequest.cancel(now, command.commandIdempotencyKey()));
        notificationPort.notifyDecided(saved);
        auditService.record(
            GovernanceAuditRecord.Action.APPROVAL_CANCELLED, command.cancelledBy(), approvalRequest.sourceDomain(),
            approvalRequest.sourceRequestId(), null, null, command.reason(), command.correlationId(), command.causationId(),
            saved.ticketId(), saved.approvalRequestId(), saved.policyDecisionId(),
            ApprovalCancelledEvent.from(saved, command.reason(), command.cancelledBy(), command.correlationId(), command.causationId())
        );
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("causationId", command.causationId())
            .addKeyValue("approvalRequestId", saved.approvalRequestId())
            .addKeyValue("policyDecisionId", saved.policyDecisionId())
            .addKeyValue("sourceDomain", saved.sourceDomain())
            .addKeyValue("sourceRequestId", saved.sourceRequestId())
            .addKeyValue("ticketId", saved.ticketId())
            .addKeyValue("workflowInstanceId", saved.workflowInstanceId())
            .log("approval cancelled");
        return saved;
    }

    /**
     * SPEC-PG-022 (04-use-cases §UC-PG-006, 03-state-machine §Override State
     * Machine: {@code OVERRIDE_APPROVED -> OVERRIDE_USED}). Mirrors {@link
     * #cancel}'s own structure: lock the row first, re-validate request
     * linkage (INV-PG-005), then treat a matching {@code
     * commandIdempotencyKey} against an already-{@code USED} request as an
     * idempotent replay and a different key as a conflict, audited the same
     * way {@link #cancel} audits {@code APPROVAL_CANCEL_CONFLICT}. {@code
     * ApprovalRequest#use} itself enforces that this is a {@code
     * POLICY_OVERRIDE} request, that it is currently {@code APPROVED}, and
     * that it has not lapsed past {@code expiresAt}.
     */
    @Transactional
    public ApprovalRequest use(UseOverrideCommand command) {
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(command.approvalRequestId())
            .orElseThrow(() -> new ApprovalRequestNotFoundException(command.approvalRequestId()));

        if (!approvalRequest.matches(command.sourceRequestId(), command.requestHash())) {
            throw new ApprovalRequestMismatchException(approvalRequest.approvalRequestId());
        }

        if (approvalRequest.status() == ApprovalStatus.USED) {
            if (command.commandIdempotencyKey().equals(approvalRequest.usedCommandIdempotencyKey())) {
                return approvalRequest;
            }
            auditService.record(
                GovernanceAuditRecord.Action.OVERRIDE_USE_CONFLICT, command.usedBy(), approvalRequest.sourceDomain(),
                approvalRequest.sourceRequestId(), null, null,
                "conflicting use rejected: override already used by a different attempt", command.correlationId(), command.causationId(),
                approvalRequest.ticketId(), approvalRequest.approvalRequestId(), approvalRequest.policyDecisionId()
            );
            throw new OverrideAlreadyUsedException(approvalRequest.approvalRequestId());
        }

        Instant now = clock.instant();
        ApprovalRequest saved = approvalRequestRepository.save(approvalRequest.use(now, command.commandIdempotencyKey()));
        auditService.record(
            GovernanceAuditRecord.Action.OVERRIDE_APPLIED, command.usedBy(), approvalRequest.sourceDomain(),
            approvalRequest.sourceRequestId(), null, null, command.reason(), command.correlationId(), command.causationId(),
            saved.ticketId(), saved.approvalRequestId(), saved.policyDecisionId()
        );
        metrics.recordOverride("USED");
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("causationId", command.causationId())
            .addKeyValue("approvalRequestId", saved.approvalRequestId())
            .addKeyValue("sourceDomain", saved.sourceDomain())
            .addKeyValue("sourceRequestId", saved.sourceRequestId())
            .addKeyValue("ticketId", saved.ticketId())
            .addKeyValue("workflowInstanceId", saved.workflowInstanceId())
            .log("override used");
        return saved;
    }

    /**
     * SPEC-PG-022 (04-use-cases §UC-PG-006, 03-state-machine §Override State
     * Machine: {@code OVERRIDE_APPROVED -> OVERRIDE_REVOKED}). Mirrors {@link
     * #use}'s own structure; unlike {@code use}, {@link
     * ApprovalRequest#revoke} does not reject a request past its {@code
     * expiresAt} — see that method's own javadoc.
     */
    @Transactional
    public ApprovalRequest revoke(RevokeOverrideCommand command) {
        ApprovalRequest approvalRequest = approvalRequestRepository.findByIdForUpdate(command.approvalRequestId())
            .orElseThrow(() -> new ApprovalRequestNotFoundException(command.approvalRequestId()));

        if (!approvalRequest.matches(command.sourceRequestId(), command.requestHash())) {
            throw new ApprovalRequestMismatchException(approvalRequest.approvalRequestId());
        }

        if (approvalRequest.status() == ApprovalStatus.REVOKED) {
            if (command.commandIdempotencyKey().equals(approvalRequest.revokedCommandIdempotencyKey())) {
                return approvalRequest;
            }
            auditService.record(
                GovernanceAuditRecord.Action.OVERRIDE_REVOKE_CONFLICT, command.revokedBy(), approvalRequest.sourceDomain(),
                approvalRequest.sourceRequestId(), null, null,
                "conflicting revoke rejected: override already revoked by a different attempt", command.correlationId(), command.causationId(),
                approvalRequest.ticketId(), approvalRequest.approvalRequestId(), approvalRequest.policyDecisionId()
            );
            throw new OverrideAlreadyRevokedException(approvalRequest.approvalRequestId());
        }

        Instant now = clock.instant();
        ApprovalRequest saved = approvalRequestRepository.save(approvalRequest.revoke(now, command.commandIdempotencyKey()));
        auditService.record(
            GovernanceAuditRecord.Action.OVERRIDE_REVOKED, command.revokedBy(), approvalRequest.sourceDomain(),
            approvalRequest.sourceRequestId(), null, null, command.reason(), command.correlationId(), command.causationId(),
            saved.ticketId(), saved.approvalRequestId(), saved.policyDecisionId()
        );
        metrics.recordOverride("REVOKED");
        log.atInfo()
            .addKeyValue("correlationId", command.correlationId())
            .addKeyValue("causationId", command.causationId())
            .addKeyValue("approvalRequestId", saved.approvalRequestId())
            .addKeyValue("sourceDomain", saved.sourceDomain())
            .addKeyValue("sourceRequestId", saved.sourceRequestId())
            .addKeyValue("ticketId", saved.ticketId())
            .addKeyValue("workflowInstanceId", saved.workflowInstanceId())
            .log("override revoked");
        return saved;
    }

    /** SPEC-PG-010 / 05-api-contracts {@code GET /approval-requests/{approvalRequestId}}. */
    @Transactional(readOnly = true)
    public ApprovalRequest findById(String approvalRequestId) {
        return approvalRequestRepository.findById(approvalRequestId)
            .orElseThrow(() -> new ApprovalRequestNotFoundException(approvalRequestId));
    }
}
