package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(schema = "governance", name = "approval_requests")
public class ApprovalRequestJpaEntity {

    @Id
    @Column(name = "approval_request_id")
    private String approvalRequestId;

    @Column(name = "request_key", nullable = false)
    private String requestKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "source_domain", nullable = false)
    private String sourceDomain;

    @Column(name = "source_request_id", nullable = false)
    private String sourceRequestId;

    @Column(name = "ticket_id")
    private String ticketId;

    @Column(name = "workflow_instance_id")
    private String workflowInstanceId;

    @Column(name = "tool_request_id")
    private String toolRequestId;

    /** SPEC-PG-015: nullable — the principal assigned to execute {@code toolRequestId}, if the caller supplied one. */
    @Column(name = "executor_id")
    private String executorId;

    /** SPEC-PG-009: nullable back-reference to the PolicyDecision this request originated from, if any. */
    @Column(name = "policy_decision_id")
    private String policyDecisionId;

    /** Deferred to phase-03's real actor-type model — see the migration's own comment. */
    @Column(name = "requested_by_type")
    private String requestedByType;

    @Column(name = "requested_by_id", nullable = false)
    private String requestedById;

    @Column(name = "approval_type", nullable = false)
    private String approvalType;

    @Column(name = "risk_level", nullable = false)
    private String riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "constraints", nullable = false, columnDefinition = "jsonb")
    private String constraintsJson;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** SPEC-PG-012: null unless {@code status == CANCELLED} — see {@code domain.approval.ApprovalRequest#cancelCommandIdempotencyKey}. */
    @Column(name = "cancel_command_idempotency_key")
    private String cancelCommandIdempotencyKey;

    /** SPEC-PG-022: null unless {@code status == USED} — see {@code domain.approval.ApprovalRequest#usedCommandIdempotencyKey}. */
    @Column(name = "used_command_idempotency_key")
    private String usedCommandIdempotencyKey;

    /** SPEC-PG-022: null unless {@code status == REVOKED} — see {@code domain.approval.ApprovalRequest#revokedCommandIdempotencyKey}. */
    @Column(name = "revoked_command_idempotency_key")
    private String revokedCommandIdempotencyKey;

    protected ApprovalRequestJpaEntity() {
    }

    public ApprovalRequestJpaEntity(
        String approvalRequestId, String requestKey, String requestHash, String sourceDomain, String sourceRequestId,
        String ticketId, String workflowInstanceId, String toolRequestId, String executorId, String policyDecisionId, String requestedByType,
        String requestedById, String approvalType, String riskLevel, String constraintsJson, String status, Instant expiresAt,
        Instant createdAt, Instant updatedAt, String cancelCommandIdempotencyKey,
        String usedCommandIdempotencyKey, String revokedCommandIdempotencyKey
    ) {
        this.approvalRequestId = approvalRequestId;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.sourceDomain = sourceDomain;
        this.sourceRequestId = sourceRequestId;
        this.ticketId = ticketId;
        this.workflowInstanceId = workflowInstanceId;
        this.toolRequestId = toolRequestId;
        this.executorId = executorId;
        this.policyDecisionId = policyDecisionId;
        this.requestedByType = requestedByType;
        this.requestedById = requestedById;
        this.approvalType = approvalType;
        this.riskLevel = riskLevel;
        this.constraintsJson = constraintsJson;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.cancelCommandIdempotencyKey = cancelCommandIdempotencyKey;
        this.usedCommandIdempotencyKey = usedCommandIdempotencyKey;
        this.revokedCommandIdempotencyKey = revokedCommandIdempotencyKey;
    }

    public String getApprovalRequestId() {
        return approvalRequestId;
    }

    public String getPolicyDecisionId() {
        return policyDecisionId;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getSourceDomain() {
        return sourceDomain;
    }

    public String getSourceRequestId() {
        return sourceRequestId;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getWorkflowInstanceId() {
        return workflowInstanceId;
    }

    public String getToolRequestId() {
        return toolRequestId;
    }

    public String getExecutorId() {
        return executorId;
    }

    public String getRequestedByType() {
        return requestedByType;
    }

    public String getRequestedById() {
        return requestedById;
    }

    public String getApprovalType() {
        return approvalType;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getConstraintsJson() {
        return constraintsJson;
    }

    public String getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCancelCommandIdempotencyKey() {
        return cancelCommandIdempotencyKey;
    }

    public String getUsedCommandIdempotencyKey() {
        return usedCommandIdempotencyKey;
    }

    public String getRevokedCommandIdempotencyKey() {
        return revokedCommandIdempotencyKey;
    }
}
