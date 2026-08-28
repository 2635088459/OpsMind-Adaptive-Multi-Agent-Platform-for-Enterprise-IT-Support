package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.RoleAssignmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpringDataRoleAssignmentJpaRepository extends JpaRepository<RoleAssignmentJpaEntity, String> {

    List<RoleAssignmentJpaEntity> findByUserIdentityId(String userIdentityId);

    @Query("""
        SELECT r FROM RoleAssignmentJpaEntity r
        WHERE r.userIdentityId = :userIdentityId AND r.roleCode = :roleCode AND r.scopeType = :scopeType
          AND ((:scopeId IS NULL AND r.scopeId IS NULL) OR r.scopeId = :scopeId)
          AND r.status = 'ACTIVE' AND r.validFrom <= :now AND (r.validUntil IS NULL OR r.validUntil > :now)
        """)
    List<RoleAssignmentJpaEntity> findActive(
        @Param("userIdentityId") String userIdentityId, @Param("roleCode") String roleCode, @Param("scopeType") String scopeType,
        @Param("scopeId") String scopeId, @Param("now") Instant now
    );

    List<RoleAssignmentJpaEntity> findByStatusAndValidFromLessThanEqual(String status, Instant now);

    List<RoleAssignmentJpaEntity> findByStatusAndValidUntilLessThanEqual(String status, Instant now);
}
