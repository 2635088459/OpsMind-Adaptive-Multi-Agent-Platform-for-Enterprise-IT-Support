package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.mapper;

import dev.opsmind.ticketworkflow.ticket.domain.model.TicketSlaCycle;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.TicketSlaCycleJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class TicketSlaCyclePersistenceMapper {

    public TicketSlaCycleJpaEntity toJpaEntity(TicketSlaCycle slaCycle) {
        return new TicketSlaCycleJpaEntity(
            slaCycle.slaCycleId(),
            slaCycle.ticketId().value(),
            slaCycle.resolutionCycleId(),
            slaCycle.policyId(),
            slaCycle.cycleNumber(),
            slaCycle.status().name(),
            slaCycle.responseDueAt(),
            slaCycle.resolutionDueAt(),
            slaCycle.accumulatedPausedSeconds(),
            slaCycle.createdAt(),
            slaCycle.updatedAt(),
            slaCycle.version()
        );
    }
}
