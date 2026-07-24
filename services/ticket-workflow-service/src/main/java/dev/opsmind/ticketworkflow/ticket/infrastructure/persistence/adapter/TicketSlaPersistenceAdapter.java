package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSlaRepository;
import dev.opsmind.ticketworkflow.ticket.domain.model.TicketSlaCycle;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataTicketSlaCycleJpaRepository;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.mapper.TicketSlaCyclePersistenceMapper;
import org.springframework.stereotype.Component;

@Component
public class TicketSlaPersistenceAdapter implements TicketSlaRepository {

    private final SpringDataTicketSlaCycleJpaRepository repository;
    private final TicketSlaCyclePersistenceMapper mapper;

    public TicketSlaPersistenceAdapter(SpringDataTicketSlaCycleJpaRepository repository, TicketSlaCyclePersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public void save(TicketSlaCycle slaCycle) {
        repository.save(mapper.toJpaEntity(slaCycle));
    }
}
