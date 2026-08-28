package com.opsmind.identity.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(schema = "identity", name = "authorization_decisions")
public class AuthorizationDecisionJpaEntity {

    @Id
    @Column(name = "decision_id")
    private String decisionId;

    @Column(name = "decision_key", nullable = false)
    private String decisionKey;

    @Column(name = "input_hash", nullable = false)
    private String inputHash;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "effect", nullable = false)
    private String effect;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluated_roles", nullable = false, columnDefinition = "jsonb")
    private String evaluatedRolesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluated_scopes", nullable = false, columnDefinition = "jsonb")
    private String evaluatedScopesJson;

    @Column(name = "ownership_satisfied", nullable = false)
    private boolean ownershipSatisfied;

    @Column(name = "assurance_level")
    private String assuranceLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", nullable = false, columnDefinition = "jsonb")
    private String reasonCodesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "constraints", nullable = false, columnDefinition = "jsonb")
    private String constraintsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    protected AuthorizationDecisionJpaEntity() {
    }

    public AuthorizationDecisionJpaEntity(
        String decisionId, String decisionKey, String inputHash, String tenantId, String actorId, String subjectId,
        String sessionId, String action, String resourceType, String resourceId, String effect, String evaluatedRolesJson,
        String evaluatedScopesJson, boolean ownershipSatisfied, String assuranceLevel, String reasonCodesJson,
        String constraintsJson, Instant createdAt, Instant expiresAt, String correlationId
    ) {
        this.decisionId = decisionId;
        this.decisionKey = decisionKey;
        this.inputHash = inputHash;
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.subjectId = subjectId;
        this.sessionId = sessionId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.effect = effect;
        this.evaluatedRolesJson = evaluatedRolesJson;
        this.evaluatedScopesJson = evaluatedScopesJson;
        this.ownershipSatisfied = ownershipSatisfied;
        this.assuranceLevel = assuranceLevel;
        this.reasonCodesJson = reasonCodesJson;
        this.constraintsJson = constraintsJson;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.correlationId = correlationId;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getDecisionKey() {
        return decisionKey;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getEffect() {
        return effect;
    }

    public String getEvaluatedRolesJson() {
        return evaluatedRolesJson;
    }

    public String getEvaluatedScopesJson() {
        return evaluatedScopesJson;
    }

    public boolean isOwnershipSatisfied() {
        return ownershipSatisfied;
    }

    public String getAssuranceLevel() {
        return assuranceLevel;
    }

    public String getReasonCodesJson() {
        return reasonCodesJson;
    }

    public String getConstraintsJson() {
        return constraintsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
