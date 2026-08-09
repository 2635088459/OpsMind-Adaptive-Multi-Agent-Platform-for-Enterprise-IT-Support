package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository;

import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.SecretDetectionDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataSecretDetectionDecisionJpaRepository extends JpaRepository<SecretDetectionDecisionJpaEntity, UUID> {
}
