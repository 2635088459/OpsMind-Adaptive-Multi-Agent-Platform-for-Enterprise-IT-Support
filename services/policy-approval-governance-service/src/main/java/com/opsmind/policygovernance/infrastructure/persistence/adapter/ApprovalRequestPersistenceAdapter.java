package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataApprovalRequestJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.ApprovalRequestMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ApprovalRequestPersistenceAdapter implements ApprovalRequestRepository {

    private final SpringDataApprovalRequestJpaRepository repository;

    public ApprovalRequestPersistenceAdapter(SpringDataApprovalRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public ApprovalRequest save(ApprovalRequest approvalRequest) {
        repository.save(ApprovalRequestMapper.toEntity(approvalRequest));
        return approvalRequest;
    }

    @Override
    public Optional<ApprovalRequest> findById(String approvalRequestId) {
        return repository.findById(approvalRequestId).map(ApprovalRequestMapper::toDomain);
    }

    @Override
    public Optional<ApprovalRequest> findByIdForUpdate(String approvalRequestId) {
        return repository.findByIdForUpdate(approvalRequestId).map(ApprovalRequestMapper::toDomain);
    }

    @Override
    public Optional<ApprovalRequest> findByRequestKey(String requestKey) {
        return repository.findFirstByRequestKeyOrderByCreatedAtAsc(requestKey).map(ApprovalRequestMapper::toDomain);
    }

    @Override
    public List<ApprovalRequest> findRequestedExpiringBefore(Instant threshold) {
        return repository.findByStatusAndExpiresAtBefore(ApprovalStatus.REQUESTED.name(), threshold).stream()
            .map(ApprovalRequestMapper::toDomain)
            .toList();
    }
}
