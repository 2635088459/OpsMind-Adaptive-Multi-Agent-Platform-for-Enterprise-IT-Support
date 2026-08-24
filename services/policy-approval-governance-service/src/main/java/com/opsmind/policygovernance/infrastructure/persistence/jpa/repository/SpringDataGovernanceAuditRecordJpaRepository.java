package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.GovernanceAuditRecordJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataGovernanceAuditRecordJpaRepository extends JpaRepository<GovernanceAuditRecordJpaEntity, String> {

    List<GovernanceAuditRecordJpaEntity> findByCorrelationId(String correlationId);

    /** SPEC-PG-030 (goal: "queries by ticket/source/decision/approval/policy"). */
    List<GovernanceAuditRecordJpaEntity> findByTicketId(String ticketId);

    List<GovernanceAuditRecordJpaEntity> findByApprovalRequestId(String approvalRequestId);

    List<GovernanceAuditRecordJpaEntity> findByPolicyDecisionId(String policyDecisionId);

    List<GovernanceAuditRecordJpaEntity> findBySourceRequestId(String sourceRequestId);

    List<GovernanceAuditRecordJpaEntity> findByPolicyId(String policyId);

    /** SPEC-PG-017: the chain's current tail — see {@code application.port.GovernanceAuditRepository#findMostRecentIntegrityHash}. */
    Optional<GovernanceAuditRecordJpaEntity> findFirstByOrderByRecordedAtDesc();

    /** SPEC-PG-031: the full walk order chain verification and compliance reporting need. */
    List<GovernanceAuditRecordJpaEntity> findAllByOrderByRecordedAtAsc();

    /** SPEC-PG-031: the one allowed update on this table — sets only {@code archivedAt}, never any other column. */
    @Modifying
    @Query(
        "UPDATE GovernanceAuditRecordJpaEntity r SET r.archivedAt = :archivedAt " +
            "WHERE r.recordedAt < :cutoff AND r.archivedAt IS NULL"
    )
    int archiveRecordedBefore(@Param("cutoff") Instant cutoff, @Param("archivedAt") Instant archivedAt);
}
