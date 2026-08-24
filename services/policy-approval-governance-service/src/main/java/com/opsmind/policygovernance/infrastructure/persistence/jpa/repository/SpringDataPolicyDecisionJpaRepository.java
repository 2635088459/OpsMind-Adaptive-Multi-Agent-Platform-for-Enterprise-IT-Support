package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataPolicyDecisionJpaRepository extends JpaRepository<PolicyDecisionJpaEntity, String> {

    Optional<PolicyDecisionJpaEntity> findFirstByDecisionKeyOrderByCreatedAtAsc(String decisionKey);

    /** SPEC-PG-033: "poison decision review" — see {@code application.port.PolicyDecisionRepository#findEvaluationFailed}. */
    List<PolicyDecisionJpaEntity> findByEvaluationFailedTrue();
}
