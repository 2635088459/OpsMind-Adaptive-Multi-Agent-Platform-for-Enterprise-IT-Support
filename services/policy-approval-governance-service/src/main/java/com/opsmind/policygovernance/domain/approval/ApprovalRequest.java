package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An approval process waiting for a human or governance principal decision
 * (01-domain-model §ApprovalRequest). Every terminal transition
 * (approve/deny/cancel/expire/supersede) is only legal from {@code
 * REQUESTED} and is irreversible once applied (03-state-machine §Approval
 * Request State Machine), and an incoming {@link ApprovalDecision} must
 * match this request's identity and request hash (INV-PG-005) before it can
 * be applied.
 *
 * <p>{@code policyDecisionId} is SPEC-PG-009's own addition: 01-domain-model
 * §Aggregate Boundary says {@code PolicyDecision} and {@code ApprovalRequest}
 * "may be linked, but are not strictly one-to-one" — 03-state-machine's
 * {@code APPROVAL_REQUIRED -> APPROVAL_LINKED} transition is that link, and
 * its own words say "approval lifecycle is represented by ApprovalRequest,"
 * so the back-reference lives here, not on {@link
 * com.opsmind.policygovernance.domain.decision.PolicyDecision} (which stays
 * a pure snapshot, per that type's own javadoc). It is nullable because an
 * approval request may exist without ever having gone through a policy
 * evaluation first (04-use-cases §UC-PG-002: "05/02/03 submits approval
 * request" names no such prerequisite).
 *
 * <p>{@code executorId} is SPEC-PG-015's own addition (11-security
 * §Separation Of Duties: "forbid ... tool execution worker approving the
 * corresponding tool request"): the identity of the principal that will
 * carry out {@code toolRequestId} once approved, if the requesting domain
 * (05 Tool Gateway) knows and supplies it. It is nullable for the same
 * reason {@code policyDecisionId} is — 06 must not fabricate an executor
 * identity it was never given (INV-PG-001: 06 performs no business side
 * effects and has no visibility into 05's own execution assignment beyond
 * what the request explicitly carries), so the separation-of-duties check
 * it enables in {@code ApprovalService#decide} is a no-op whenever it is
 * absent rather than a false negative.
 *
 * <p>{@code usedCommandIdempotencyKey}/{@code revokedCommandIdempotencyKey}
 * are SPEC-PG-022's own addition, one per new terminal command the same way
 * {@code cancelCommandIdempotencyKey} is one for cancel (SPEC-PG-012) —
 * neither {@link #use} nor {@link #revoke} creates a new {@link
 * ApprovalDecision} row (the request was already decided {@code APPROVED}),
 * so each needs its own idempotency guard carried on the request itself.
 */
public final class ApprovalRequest {

    private final String approvalRequestId;
    private final String requestKey;
    private final String requestHash;
    private final String sourceDomain;
    private final String sourceRequestId;
    private final String ticketId;
    private final String workflowInstanceId;
    private final String toolRequestId;
    private final String executorId;
    private final String policyDecisionId;
    private final String requestedBy;
    private final ApprovalType approvalType;
    private final RiskLevel riskLevel;
    private final List<Constraint> constraints;
    private final ApprovalStatus status;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String cancelCommandIdempotencyKey;
    private final String usedCommandIdempotencyKey;
    private final String revokedCommandIdempotencyKey;

    private ApprovalRequest(
        String approvalRequestId,
        String requestKey,
        String requestHash,
        String sourceDomain,
        String sourceRequestId,
        String ticketId,
        String workflowInstanceId,
        String toolRequestId,
        String executorId,
        String policyDecisionId,
        String requestedBy,
        ApprovalType approvalType,
        RiskLevel riskLevel,
        List<Constraint> constraints,
        ApprovalStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        String cancelCommandIdempotencyKey,
        String usedCommandIdempotencyKey,
        String revokedCommandIdempotencyKey
    ) {
        this.approvalRequestId = Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        this.requestKey = Objects.requireNonNull(requestKey, "requestKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.sourceDomain = Objects.requireNonNull(sourceDomain, "sourceDomain");
        this.sourceRequestId = Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        this.ticketId = ticketId;
        this.workflowInstanceId = workflowInstanceId;
        this.toolRequestId = toolRequestId;
        this.executorId = executorId;
        this.policyDecisionId = policyDecisionId;
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.approvalType = Objects.requireNonNull(approvalType, "approvalType");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        this.constraints = List.copyOf(constraints == null ? List.of() : constraints);
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.cancelCommandIdempotencyKey = cancelCommandIdempotencyKey;
        this.usedCommandIdempotencyKey = usedCommandIdempotencyKey;
        this.revokedCommandIdempotencyKey = revokedCommandIdempotencyKey;
    }

    public static ApprovalRequest requested(
        String approvalRequestId,
        String requestKey,
        String requestHash,
        String sourceDomain,
        String sourceRequestId,
        String ticketId,
        String workflowInstanceId,
        String toolRequestId,
        String executorId,
        String policyDecisionId,
        String requestedBy,
        ApprovalType approvalType,
        RiskLevel riskLevel,
        List<Constraint> constraints,
        Instant expiresAt,
        Instant createdAt
    ) {
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, executorId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, ApprovalStatus.REQUESTED, expiresAt, createdAt, createdAt, null, null, null
        );
    }

    /**
     * Rehydrates a previously-persisted request exactly as stored, at
     * whatever status it currently holds — unlike {@link #requested}, this
     * does not assert {@code REQUESTED}. Used only by
     * {@code infrastructure.persistence.mapper.ApprovalRequestMapper}.
     */
    public static ApprovalRequest reconstruct(
        String approvalRequestId,
        String requestKey,
        String requestHash,
        String sourceDomain,
        String sourceRequestId,
        String ticketId,
        String workflowInstanceId,
        String toolRequestId,
        String executorId,
        String policyDecisionId,
        String requestedBy,
        ApprovalType approvalType,
        RiskLevel riskLevel,
        List<Constraint> constraints,
        ApprovalStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        String cancelCommandIdempotencyKey,
        String usedCommandIdempotencyKey,
        String revokedCommandIdempotencyKey
    ) {
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, executorId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, status, expiresAt, createdAt, updatedAt, cancelCommandIdempotencyKey,
            usedCommandIdempotencyKey, revokedCommandIdempotencyKey
        );
    }

    /** INV-PG-005: true only if {@code sourceRequestId} and {@code requestHash} both match this request. */
    public boolean matches(String sourceRequestId, String requestHash) {
        return this.sourceRequestId.equals(sourceRequestId) && this.requestHash.equals(requestHash);
    }

    public ApprovalRequest approve(ApprovalDecision decision, Instant now) {
        requireDecisionMatches(decision);
        if (decision.decision() != ApprovalDecision.Outcome.APPROVED) {
            throw new IllegalStateException("approve() called with a non-APPROVED decision");
        }
        return transitionTo(ApprovalStatus.APPROVED, now);
    }

    public ApprovalRequest deny(ApprovalDecision decision, Instant now) {
        requireDecisionMatches(decision);
        if (decision.decision() != ApprovalDecision.Outcome.DENIED) {
            throw new IllegalStateException("deny() called with a non-DENIED decision");
        }
        return transitionTo(ApprovalStatus.DENIED, now);
    }

    /**
     * SPEC-PG-012: {@code commandIdempotencyKey} is cancel's own idempotency
     * guard (09-concurrency-and-idempotency), distinct from the grant/deny
     * {@code commandIdempotencyKey} on {@link ApprovalDecision} — cancel
     * never creates a decision row, so the key is carried on the request
     * itself instead. {@code ApprovalService#cancel} compares an incoming
     * key against this one to tell a genuine retry of the same cancel
     * command apart from a conflicting second cancel attempt once the
     * request is already {@code CANCELLED}.
     */
    public ApprovalRequest cancel(Instant now, String commandIdempotencyKey) {
        Objects.requireNonNull(commandIdempotencyKey, "commandIdempotencyKey");
        if (status != ApprovalStatus.REQUESTED) {
            throw new IllegalApprovalTransitionException(status, ApprovalStatus.CANCELLED);
        }
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, executorId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, ApprovalStatus.CANCELLED, expiresAt, createdAt, Objects.requireNonNull(now, "now"),
            commandIdempotencyKey, usedCommandIdempotencyKey, revokedCommandIdempotencyKey
        );
    }

    public ApprovalRequest expire(Instant now) {
        return transitionTo(ApprovalStatus.EXPIRED, now);
    }

    public ApprovalRequest supersede(Instant now) {
        return transitionTo(ApprovalStatus.SUPERSEDED, now);
    }

    /**
     * SPEC-PG-022 (03-state-machine §Override State Machine: {@code
     * OVERRIDE_APPROVED -> OVERRIDE_USED}). Legal only from {@code APPROVED},
     * only for a {@link ApprovalType#POLICY_OVERRIDE} request, and only
     * before {@code expiresAt} (UC-PG-006: "valid only within limited scope
     * and time window") — {@link OverrideExpiredException} covers the case
     * where the request is still {@code APPROVED} in the database but its
     * time window has already lapsed (the expiry worker only scans {@code
     * REQUESTED} rows, so this cannot be assumed to have already been
     * caught). {@code commandIdempotencyKey} is this transition's own
     * idempotency guard, the same reasoning {@link #cancel} documents for
     * its own key.
     */
    public ApprovalRequest use(Instant now, String commandIdempotencyKey) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(commandIdempotencyKey, "commandIdempotencyKey");
        requireOverrideType();
        if (status != ApprovalStatus.APPROVED) {
            throw new IllegalApprovalTransitionException(status, ApprovalStatus.USED);
        }
        if (expiresAt != null && now.isAfter(expiresAt)) {
            throw new OverrideExpiredException(approvalRequestId);
        }
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, executorId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, ApprovalStatus.USED, expiresAt, createdAt, now,
            cancelCommandIdempotencyKey, commandIdempotencyKey, revokedCommandIdempotencyKey
        );
    }

    /**
     * SPEC-PG-022 (03-state-machine §Override State Machine: {@code
     * OVERRIDE_APPROVED -> OVERRIDE_REVOKED}). Legal only from {@code
     * APPROVED} and only for a {@link ApprovalType#POLICY_OVERRIDE} request
     * — governance withdrawing an approved-but-not-yet-used override before
     * it is exercised. Unlike {@link #use}, an already-lapsed {@code
     * expiresAt} does not block a revoke: formally closing out an override
     * that is technically already past its window is still a legitimate
     * governance action, not an error.
     */
    public ApprovalRequest revoke(Instant now, String commandIdempotencyKey) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(commandIdempotencyKey, "commandIdempotencyKey");
        requireOverrideType();
        if (status != ApprovalStatus.APPROVED) {
            throw new IllegalApprovalTransitionException(status, ApprovalStatus.REVOKED);
        }
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, executorId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, ApprovalStatus.REVOKED, expiresAt, createdAt, now,
            cancelCommandIdempotencyKey, usedCommandIdempotencyKey, commandIdempotencyKey
        );
    }

    private void requireOverrideType() {
        if (approvalType != ApprovalType.POLICY_OVERRIDE) {
            throw new NotAnOverrideRequestException(approvalRequestId, approvalType);
        }
    }

    private void requireDecisionMatches(ApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (!decision.approvalRequestId().equals(approvalRequestId)) {
            throw new ApprovalRequestMismatchException(approvalRequestId);
        }
    }

    private ApprovalRequest transitionTo(ApprovalStatus target, Instant now) {
        if (status != ApprovalStatus.REQUESTED) {
            throw new IllegalApprovalTransitionException(status, target);
        }
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, executorId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, target, expiresAt, createdAt, Objects.requireNonNull(now, "now"),
            cancelCommandIdempotencyKey, usedCommandIdempotencyKey, revokedCommandIdempotencyKey
        );
    }

    public String approvalRequestId() {
        return approvalRequestId;
    }

    public String requestKey() {
        return requestKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public String sourceDomain() {
        return sourceDomain;
    }

    public String sourceRequestId() {
        return sourceRequestId;
    }

    public String ticketId() {
        return ticketId;
    }

    public String workflowInstanceId() {
        return workflowInstanceId;
    }

    public String policyDecisionId() {
        return policyDecisionId;
    }

    public String toolRequestId() {
        return toolRequestId;
    }

    /** SPEC-PG-015: nullable — see this class's own javadoc for why. */
    public String executorId() {
        return executorId;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public ApprovalType approvalType() {
        return approvalType;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public List<Constraint> constraints() {
        return constraints;
    }

    public ApprovalStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** SPEC-PG-012: null unless {@code status == CANCELLED} — see {@link #cancel(Instant, String)}. */
    public String cancelCommandIdempotencyKey() {
        return cancelCommandIdempotencyKey;
    }

    /** SPEC-PG-022: null unless {@code status == USED} — see {@link #use(Instant, String)}. */
    public String usedCommandIdempotencyKey() {
        return usedCommandIdempotencyKey;
    }

    /** SPEC-PG-022: null unless {@code status == REVOKED} — see {@link #revoke(Instant, String)}. */
    public String revokedCommandIdempotencyKey() {
        return revokedCommandIdempotencyKey;
    }
}
