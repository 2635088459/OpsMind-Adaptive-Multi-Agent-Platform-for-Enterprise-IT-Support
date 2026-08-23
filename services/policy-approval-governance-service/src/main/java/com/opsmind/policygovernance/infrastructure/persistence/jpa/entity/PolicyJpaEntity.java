package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(schema = "governance", name = "policies")
public class PolicyJpaEntity {

    @Id
    @Column(name = "policy_id")
    private String policyId;

    @Column(name = "policy_name", nullable = false)
    private String policyName;

    @Column(name = "scope", nullable = false)
    private String scope;

    @Column(name = "current_published_version")
    private Integer currentPublishedVersion;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PolicyJpaEntity() {
    }

    public PolicyJpaEntity(
        String policyId, String policyName, String scope, Integer currentPublishedVersion,
        String status, String createdBy, Instant createdAt, Instant updatedAt
    ) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.scope = scope;
        this.currentPublishedVersion = currentPublishedVersion;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getPolicyId() {
        return policyId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getScope() {
        return scope;
    }

    public Integer getCurrentPublishedVersion() {
        return currentPublishedVersion;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
