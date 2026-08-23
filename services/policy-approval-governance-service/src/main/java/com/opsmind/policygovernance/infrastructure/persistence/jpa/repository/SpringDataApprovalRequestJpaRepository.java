package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ApprovalRequestJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataApprovalRequestJpaRepository extends JpaRepository<ApprovalRequestJpaEntity, String> {

    Optional<ApprovalRequestJpaEntity> findFirstByRequestKeyOrderByCreatedAtAsc(String requestKey);

    List<ApprovalRequestJpaEntity> findByStatusAndExpiresAtBefore(String status, Instant threshold);

    /** {@code SELECT ... FOR UPDATE} — see the port method's own javadoc. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ApprovalRequestJpaEntity a WHERE a.approvalRequestId = :id")
    Optional<ApprovalRequestJpaEntity> findByIdForUpdate(@Param("id") String id);
}
