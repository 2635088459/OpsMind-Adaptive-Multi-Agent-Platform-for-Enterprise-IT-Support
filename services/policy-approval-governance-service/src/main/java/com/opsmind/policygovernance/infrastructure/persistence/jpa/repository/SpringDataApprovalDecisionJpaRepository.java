package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ApprovalDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataApprovalDecisionJpaRepository extends JpaRepository<ApprovalDecisionJpaEntity, String> {

    Optional<ApprovalDecisionJpaEntity> findByApprovalRequestId(String approvalRequestId);
}
