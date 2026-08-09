package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository;

import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.SupportQueueAuthorizationDecisionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataSupportQueueAuthorizationDecisionJpaRepository extends JpaRepository<SupportQueueAuthorizationDecisionJpaEntity, UUID> {
}
