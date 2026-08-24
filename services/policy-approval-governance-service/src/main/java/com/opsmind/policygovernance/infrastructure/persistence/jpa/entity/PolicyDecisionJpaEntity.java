package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(schema = "governance", name = "policy_decisions")
public class PolicyDecisionJpaEntity {

    @Id
    @Column(name = "policy_decision_id")
    private String policyDecisionId;

    @Column(name = "decision_key", nullable = false)
    private String decisionKey;

    @Column(name = "input_hash", nullable = false)
    private String inputHash;

    @Column(name = "subject_type", nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "source_domain", nullable = false)
    private String sourceDomain;

    @Column(name = "source_request_id", nullable = false)
    private String sourceRequestId;

    @Column(name = "ticket_id")
    private String ticketId;

    @Column(name = "workflow_instance_id")
    private String workflowInstanceId;

    @Column(name = "effect", nullable = false)
    private String effect;

    @Column(name = "risk_level", nullable = false)
    private String riskLevel;

    @Column(name = "approval_required", nullable = false)
    private boolean approvalRequired;

    @Column(name = "evaluation_failed", nullable = false)
    private boolean evaluationFailed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "constraints", nullable = false, columnDefinition = "jsonb")
    private String constraintsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", nullable = false, columnDefinition = "jsonb")
    private String reasonCodesJson;

    @Column(name = "policy_id", nullable = false)
    private String policyId;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** SPEC-PG-021: true only when an effective version existed but the evaluator itself threw — see the domain type's own javadoc. */
    @Column(name = "degraded", nullable = false)
    private boolean degraded;

    protected PolicyDecisionJpaEntity() {
    }

    public PolicyDecisionJpaEntity(
        String policyDecisionId, String decisionKey, String inputHash, String subjectType, String subjectId,
        String actionType, String resourceType, String resourceId, String tenantId, String sourceDomain,
        String sourceRequestId, String ticketId, String workflowInstanceId, String effect, String riskLevel,
        boolean approvalRequired, boolean evaluationFailed, String constraintsJson, String reasonCodesJson, String policyId,
        String policyVersion, Instant createdAt, Instant expiresAt, boolean degraded
    ) {
        this.policyDecisionId = policyDecisionId;
        this.decisionKey = decisionKey;
        this.inputHash = inputHash;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.actionType = actionType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.tenantId = tenantId;
        this.sourceDomain = sourceDomain;
        this.sourceRequestId = sourceRequestId;
        this.ticketId = ticketId;
        this.workflowInstanceId = workflowInstanceId;
        this.effect = effect;
        this.riskLevel = riskLevel;
        this.approvalRequired = approvalRequired;
        this.evaluationFailed = evaluationFailed;
        this.constraintsJson = constraintsJson;
        this.reasonCodesJson = reasonCodesJson;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.degraded = degraded;
    }

    public String getPolicyDecisionId() {
        return policyDecisionId;
    }

    public String getDecisionKey() {
        return decisionKey;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getTenantId() {
        return tenantId;
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

    public String getEffect() {
        return effect;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    public boolean isEvaluationFailed() {
        return evaluationFailed;
    }

    public String getConstraintsJson() {
        return constraintsJson;
    }

    public String getReasonCodesJson() {
        return reasonCodesJson;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isDegraded() {
        return degraded;
    }
}
