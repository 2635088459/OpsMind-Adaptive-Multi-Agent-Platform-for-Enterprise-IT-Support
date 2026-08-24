package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.PolicyVersionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpringDataPolicyVersionJpaRepository extends JpaRepository<PolicyVersionJpaEntity, String> {

    @Query("""
        SELECT v FROM PolicyVersionJpaEntity v
        WHERE v.policyId = :policyId
          AND v.status = 'PUBLISHED'
          AND (v.effectiveFrom IS NULL OR v.effectiveFrom <= :asOf)
          AND (v.effectiveTo IS NULL OR v.effectiveTo > :asOf)
        ORDER BY v.versionNumber DESC
        """)
    List<PolicyVersionJpaEntity> findEffectiveVersions(@Param("policyId") String policyId, @Param("asOf") Instant asOf);

    /** SPEC-PG-019: backs {@code application.port.PolicyVersionRepository#findLatestVersion}. */
    java.util.Optional<PolicyVersionJpaEntity> findFirstByPolicyIdOrderByVersionNumberDesc(String policyId);

    /** SPEC-PG-020: backs {@code application.port.PolicyVersionRepository#findByPolicyIdAndVersionNumber}. */
    java.util.Optional<PolicyVersionJpaEntity> findByPolicyIdAndVersionNumber(String policyId, int versionNumber);
}
