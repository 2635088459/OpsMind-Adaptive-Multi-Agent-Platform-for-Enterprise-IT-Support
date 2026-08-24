package com.opsmind.policygovernance.infrastructure.persistence.adapter;

import com.opsmind.policygovernance.application.model.ProcessedEventRecord;
import com.opsmind.policygovernance.application.port.ProcessedEventRepository;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ProcessedEventJpaEntity;
import com.opsmind.policygovernance.infrastructure.persistence.jpa.repository.SpringDataProcessedEventJpaRepository;
import com.opsmind.policygovernance.infrastructure.persistence.mapper.ProcessedEventMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * SPEC-PG-025: relies on the real {@code uq_processed_events_event_consumer}
 * database constraint (migration V009) rather than a check-then-insert — two
 * concurrent deliveries of the same message race safely at the database
 * level; only one insert succeeds, the other's {@link DataIntegrityViolationException}
 * is the "already processed" signal, exactly the same pattern every other
 * conflict path in this service already relies on (e.g. {@code
 * uq_approval_requests_source_key}).
 *
 * <p>Deliberately has no {@code @Transactional} of its own: {@link
 * SpringDataProcessedEventJpaRepository#insertIsolated} already runs in its
 * own {@code REQUIRES_NEW} transaction — a caught JPA flush failure leaves
 * the transaction that experienced it unable to commit even once caught (a
 * JPA/Hibernate rule, not a Spring one), so the catch here must sit outside
 * any transactional boundary of this method's own, or the surrounding
 * transaction (this method's, or a caller's) would itself fail to commit
 * with {@code UnexpectedRollbackException} despite the exception being
 * "handled."
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

    @Override
    public List<ProcessedEventRecord> findByEventId(String eventId) {
        return repository.findByEventId(eventId).stream().map(ProcessedEventMapper::toDomain).toList();
    }

    /**
     * SPEC-PG-034: a derived {@code deleteBy...} query needs its own
     * transactional boundary when invoked without one already open —
     * mirrors {@code OutboxEventPersistenceAdapter#requeue}'s own javadoc
     * for exactly the same reason: {@code
     * application.ProcessedEventAdminService#backfill} already provides
     * one in production, but this adapter should not depend on every
     * future caller remembering to.
     */
    @Override
    @Transactional
    public boolean deleteIfExists(String eventId, String consumerName) {
        return repository.deleteByEventIdAndConsumerName(eventId, consumerName) > 0;
    }
}
