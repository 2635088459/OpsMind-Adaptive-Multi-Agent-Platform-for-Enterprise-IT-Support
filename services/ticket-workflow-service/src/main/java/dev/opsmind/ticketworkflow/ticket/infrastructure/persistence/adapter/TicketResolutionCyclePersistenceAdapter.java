package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionCycleRepository;
import dev.opsmind.ticketworkflow.ticket.domain.model.TicketResolutionCycle;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataTicketResolutionCycleJpaRepository;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.mapper.TicketResolutionCyclePersistenceMapper;
import org.springframework.stereotype.Component;

@Component
public class TicketResolutionCyclePersistenceAdapter implements TicketResolutionCycleRepository {

    private final SpringDataTicketResolutionCycleJpaRepository repository;
    private final TicketResolutionCyclePersistenceMapper mapper;

    public TicketResolutionCyclePersistenceAdapter(
        SpringDataTicketResolutionCycleJpaRepository repository,
        TicketResolutionCyclePersistenceMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public void save(TicketResolutionCycle cycle) {
        repository.save(mapper.toJpaEntity(cycle));
    }
}
