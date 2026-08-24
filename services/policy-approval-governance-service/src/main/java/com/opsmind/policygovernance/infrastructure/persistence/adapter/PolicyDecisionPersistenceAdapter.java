package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.PolicyDecisionRepository;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataPolicyDecisionJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.PolicyDecisionMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PolicyDecisionPersistenceAdapter implements PolicyDecisionRepository {

    private final SpringDataPolicyDecisionJpaRepository repository;

    public PolicyDecisionPersistenceAdapter(SpringDataPolicyDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public PolicyDecision save(PolicyDecision decision) {
        repository.save(PolicyDecisionMapper.toEntity(decision));
        return decision;
    }

    @Override
    public Optional<PolicyDecision> findById(String policyDecisionId) {
        return repository.findById(policyDecisionId).map(PolicyDecisionMapper::toDomain);
    }

    @Override
    public Optional<PolicyDecision> findByDecisionKey(String decisionKey) {
        return repository.findFirstByDecisionKeyOrderByCreatedAtAsc(decisionKey).map(PolicyDecisionMapper::toDomain);
    }

    @Override
    public List<PolicyDecision> findEvaluationFailed() {
        return repository.findByEvaluationFailedTrue().stream().map(PolicyDecisionMapper::toDomain).toList();
    }
}
