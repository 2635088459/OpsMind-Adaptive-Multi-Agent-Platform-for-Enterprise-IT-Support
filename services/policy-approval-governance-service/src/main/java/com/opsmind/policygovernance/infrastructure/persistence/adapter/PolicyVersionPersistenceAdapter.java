package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.PolicyVersionRepository;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyVersionJpaEntity;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataPolicyVersionJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.PolicyVersionMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@Component
public class PolicyVersionPersistenceAdapter implements PolicyVersionRepository {

    private final SpringDataPolicyVersionJpaRepository repository;

    public PolicyVersionPersistenceAdapter(SpringDataPolicyVersionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public PolicyVersion save(PolicyVersion policyVersion) {
        repository.save(PolicyVersionMapper.toEntity(policyVersion));
        return policyVersion;
    }

    @Override
    public Optional<PolicyVersion> findById(String policyVersionId) {
        return repository.findById(policyVersionId).map(PolicyVersionMapper::toDomain);
    }

    @Override
    public Optional<PolicyVersion> findEffectiveVersion(String policyId, Instant asOf) {
        return repository.findEffectiveVersions(policyId, asOf).stream()
            .max(Comparator.comparingInt(PolicyVersionJpaEntity::getVersionNumber))
            .map(PolicyVersionMapper::toDomain);
    }
}
