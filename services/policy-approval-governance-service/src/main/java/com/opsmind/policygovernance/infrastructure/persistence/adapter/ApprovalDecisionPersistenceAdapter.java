package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.ApprovalDecisionRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalDecision;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataApprovalDecisionJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.ApprovalDecisionMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@code UNIQUE (approval_request_id)} constraint on {@code
 * governance.approval_decisions} (V006) is what actually makes concurrent
 * grant/deny attempts against the same request safe: {@link #save} on the
 * losing thread throws a {@code DataIntegrityViolationException} instead of
 * silently creating a second final decision — see the JPA entity's own
 * javadoc.
 */
@Component
public class ApprovalDecisionPersistenceAdapter implements ApprovalDecisionRepository {

    private final SpringDataApprovalDecisionJpaRepository repository;

    public ApprovalDecisionPersistenceAdapter(SpringDataApprovalDecisionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ApprovalDecision save(ApprovalDecision decision) {
        repository.saveAndFlush(ApprovalDecisionMapper.toEntity(decision));
        return decision;
    }

    @Override
    public Optional<ApprovalDecision> findByApprovalRequestId(String approvalRequestId) {
        return repository.findByApprovalRequestId(approvalRequestId).map(ApprovalDecisionMapper::toDomain);
    }
}
