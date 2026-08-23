package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(schema = "governance", name = "policy_versions")
public class PolicyVersionJpaEntity {

    @Id
    @Column(name = "policy_version_id")
    private String policyVersionId;

    @Column(name = "policy_id", nullable = false)
    private String policyId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "status", nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false, columnDefinition = "jsonb")
    private String rulesJson;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "published_by")
    private String publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected PolicyVersionJpaEntity() {
    }

    public PolicyVersionJpaEntity(
        String policyVersionId, String policyId, int versionNumber, String status, String rulesJson,
        Instant effectiveFrom, Instant effectiveTo, String createdBy, String reviewedBy, String publishedBy, Instant publishedAt
    ) {
        this.policyVersionId = policyVersionId;
        this.policyId = policyId;
        this.versionNumber = versionNumber;
        this.status = status;
        this.rulesJson = rulesJson;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = createdBy;
        this.reviewedBy = reviewedBy;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
    }

    public String getPolicyVersionId() {
        return policyVersionId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getStatus() {
        return status;
    }

    public String getRulesJson() {
        return rulesJson;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
