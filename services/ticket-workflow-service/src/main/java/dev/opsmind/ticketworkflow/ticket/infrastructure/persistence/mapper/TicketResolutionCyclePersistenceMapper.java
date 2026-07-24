package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.mapper;

import dev.opsmind.ticketworkflow.ticket.domain.model.TicketResolutionCycle;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.TicketResolutionCycleJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class TicketResolutionCyclePersistenceMapper {

    public TicketResolutionCycleJpaEntity toJpaEntity(TicketResolutionCycle cycle) {
        return new TicketResolutionCycleJpaEntity(
            cycle.resolutionCycleId(),
            cycle.ticketId().value(),
            cycle.cycleNumber(),
            cycle.cycleStatus().name(),
            cycle.openedAt()
        );
    }
}
