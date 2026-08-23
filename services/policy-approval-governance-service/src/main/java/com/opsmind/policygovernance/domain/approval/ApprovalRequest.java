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
    private final String policyDecisionId;
    private final String requestedBy;
    private final ApprovalType approvalType;
    private final RiskLevel riskLevel;
    private final List<Constraint> constraints;
    private final ApprovalStatus status;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private ApprovalRequest(
        String approvalRequestId,
        String requestKey,
        String requestHash,
        String sourceDomain,
        String sourceRequestId,
        String ticketId,
        String workflowInstanceId,
        String toolRequestId,
        String policyDecisionId,
        String requestedBy,
        ApprovalType approvalType,
        RiskLevel riskLevel,
        List<Constraint> constraints,
        ApprovalStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.approvalRequestId = Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        this.requestKey = Objects.requireNonNull(requestKey, "requestKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.sourceDomain = Objects.requireNonNull(sourceDomain, "sourceDomain");
        this.sourceRequestId = Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        this.ticketId = ticketId;
        this.workflowInstanceId = workflowInstanceId;
        this.toolRequestId = toolRequestId;
        this.policyDecisionId = policyDecisionId;
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.approvalType = Objects.requireNonNull(approvalType, "approvalType");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        this.constraints = List.copyOf(constraints == null ? List.of() : constraints);
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
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
            ticketId, workflowInstanceId, toolRequestId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, ApprovalStatus.REQUESTED, expiresAt, createdAt, createdAt
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
        String policyDecisionId,
        String requestedBy,
        ApprovalType approvalType,
        RiskLevel riskLevel,
        List<Constraint> constraints,
        ApprovalStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new ApprovalRequest(
            approvalRequestId, requestKey, requestHash, sourceDomain, sourceRequestId,
            ticketId, workflowInstanceId, toolRequestId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, status, expiresAt, createdAt, updatedAt
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

    public ApprovalRequest cancel(Instant now) {
        return transitionTo(ApprovalStatus.CANCELLED, now);
    }

    public ApprovalRequest expire(Instant now) {
        return transitionTo(ApprovalStatus.EXPIRED, now);
    }

    public ApprovalRequest supersede(Instant now) {
        return transitionTo(ApprovalStatus.SUPERSEDED, now);
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
            ticketId, workflowInstanceId, toolRequestId, policyDecisionId, requestedBy, approvalType, riskLevel,
            constraints, target, expiresAt, createdAt, Objects.requireNonNull(now, "now")
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
}
