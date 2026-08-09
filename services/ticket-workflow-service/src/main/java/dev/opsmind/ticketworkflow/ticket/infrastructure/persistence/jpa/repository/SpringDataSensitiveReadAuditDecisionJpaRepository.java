package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository;

import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.SensitiveReadAuditDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataSensitiveReadAuditDecisionJpaRepository extends JpaRepository<SensitiveReadAuditDecisionJpaEntity, UUID> {
}
