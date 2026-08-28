package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.ProcessedEventRepository;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.ProcessedEventJpaEntity;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataProcessedEventJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * SPEC-UA-003: relies on the real {@code uq_processed_events_event_consumer}
 * database constraint (migration V010) rather than check-then-insert — two
 * concurrent deliveries of the same message race safely at the database
 * level. Deliberately has no {@code @Transactional} of its own — see
 * {@link SpringDataProcessedEventJpaRepository#insertIsolated}'s own javadoc
 * for why the catch must sit outside any transactional boundary of this
 * method's own.
 */
@Component
public class ProcessedEventPersistenceAdapter implements ProcessedEventRepository {

    private final SpringDataProcessedEventJpaRepository repository;
    private final Clock clock;

    public ProcessedEventPersistenceAdapter(SpringDataProcessedEventJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public boolean markProcessedIfNew(String eventId, String consumerName, String eventType) {
        try {
            repository.insertIsolated(new ProcessedEventJpaEntity(UUID.randomUUID(), eventId, consumerName, eventType, clock.instant()));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
