package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPolicyJpaRepository extends JpaRepository<PolicyJpaEntity, String> {
}
