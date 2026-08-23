package com.opsmind.policygovernance.infrastructure.persistence.mapper;

import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.domain.policy.PolicyLifecycleStatus;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyJpaEntity;

public final class PolicyMapper {

    private PolicyMapper() {
    }

    public static PolicyJpaEntity toEntity(Policy policy) {
        return new PolicyJpaEntity(
            policy.policyId(), policy.policyName(), policy.scope(), policy.currentPublishedVersion(),
            policy.status().name(), policy.createdBy(), policy.createdAt(), policy.updatedAt()
        );
    }

    public static Policy toDomain(PolicyJpaEntity entity) {
        return new Policy(
            entity.getPolicyId(), entity.getPolicyName(), entity.getScope(), entity.getCurrentPublishedVersion(),
            PolicyLifecycleStatus.valueOf(entity.getStatus()), entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }
}
