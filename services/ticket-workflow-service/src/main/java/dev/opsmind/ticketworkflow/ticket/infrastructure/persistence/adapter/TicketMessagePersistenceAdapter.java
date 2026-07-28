package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketMessageRepository;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessage;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataTicketMessageJpaRepository;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.mapper.TicketMessagePersistenceMapper;
import org.springframework.stereotype.Component;

@Component
public class TicketMessagePersistenceAdapter implements TicketMessageRepository {

    private final SpringDataTicketMessageJpaRepository repository;
    private final TicketMessagePersistenceMapper mapper;

    public TicketMessagePersistenceAdapter(SpringDataTicketMessageJpaRepository repository, TicketMessagePersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public void save(TicketMessage message) {
        repository.save(mapper.toJpaEntity(message));
    }
}
