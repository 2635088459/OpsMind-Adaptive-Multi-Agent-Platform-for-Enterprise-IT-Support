package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.PolicyRepository;
import com.opsmind.policygovernance.domain.policy.Policy;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataPolicyJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.PolicyMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PolicyPersistenceAdapter implements PolicyRepository {

    private final SpringDataPolicyJpaRepository repository;

    public PolicyPersistenceAdapter(SpringDataPolicyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Policy save(Policy policy) {
        repository.save(PolicyMapper.toEntity(policy));
        return policy;
    }

    @Override
    public Optional<Policy> findById(String policyId) {
        return repository.findById(policyId).map(PolicyMapper::toDomain);
    }
}
