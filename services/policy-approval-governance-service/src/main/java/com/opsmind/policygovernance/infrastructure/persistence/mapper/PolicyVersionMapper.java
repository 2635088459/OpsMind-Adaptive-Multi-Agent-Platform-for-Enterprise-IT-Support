package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.policygovernance.domain.policy.PolicyRule;
import com.opsmind.policygovernance.domain.policy.PolicyStatus;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyVersionJpaEntity;

import java.util.List;

public final class PolicyVersionMapper {

    private static final TypeReference<List<PolicyRule>> RULES_TYPE = new TypeReference<>() {
    };

    private PolicyVersionMapper() {
    }

    public static PolicyVersionJpaEntity toEntity(PolicyVersion version) {
        return new PolicyVersionJpaEntity(
            version.policyVersionId(), version.policyId(), version.versionNumber(), version.status().name(),
            JsonSupport.writeList(version.rules()), version.effectiveFrom(), version.effectiveTo(),
            version.createdBy(), version.reviewedBy(), version.publishedBy(), version.publishedAt()
        );
    }

    public static PolicyVersion toDomain(PolicyVersionJpaEntity entity) {
        return PolicyVersion.reconstruct(
            entity.getPolicyVersionId(), entity.getPolicyId(), entity.getVersionNumber(),
            PolicyStatus.valueOf(entity.getStatus()), JsonSupport.readList(entity.getRulesJson(), RULES_TYPE),
            entity.getEffectiveFrom(), entity.getEffectiveTo(), entity.getCreatedBy(),
            entity.getReviewedBy(), entity.getPublishedBy(), entity.getPublishedAt()
        );
    }
}
