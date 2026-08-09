package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository;

import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.StepUpAuthenticationDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataStepUpAuthenticationDecisionJpaRepository extends JpaRepository<StepUpAuthenticationDecisionJpaEntity, UUID> {
}
