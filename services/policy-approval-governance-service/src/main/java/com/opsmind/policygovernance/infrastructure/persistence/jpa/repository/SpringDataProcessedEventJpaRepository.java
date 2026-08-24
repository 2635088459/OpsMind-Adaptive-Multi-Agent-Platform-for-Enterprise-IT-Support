package com.opsmind.policygovernance.infrastructure.persistence.jpa.repository;

import com.opsmind.policygovernance.infrastructure.persistence.jpa.entity.ProcessedEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface SpringDataProcessedEventJpaRepository extends JpaRepository<ProcessedEventJpaEntity, UUID> {

    /** SPEC-PG-034: every consumer that has recorded this {@code eventId} as processed. */
    List<ProcessedEventJpaEntity> findByEventId(String eventId);

    /** SPEC-PG-034: the "backfill" repair action — see {@code application.port.ProcessedEventRepository#deleteIfExists}. */
    long deleteByEventIdAndConsumerName(String eventId, String consumerName);

    /**
     * SPEC-PG-025: {@code REQUIRES_NEW} so a unique-constraint violation on
     * {@code (event_id, consumer_name)} — the expected, common case for a
     * redelivered message — dooms only this isolated transaction, not
     * whatever ambient transaction the caller (e.g. {@code
     * ConsumedEventDeduplicationService#ifNew}) is already running.
     * {@code saveAndFlush} forces the constraint check to happen here,
     * inside this method's own transactional boundary, rather than being
     * deferred to a later commit the caller does not control — a caught
     * {@code DataIntegrityViolationException} from a JPA flush still leaves
     * the transaction that experienced it unable to commit even if the
     * exception is swallowed (a well-known JPA/Hibernate rule, not a
     * Spring one), so the exception must be left to propagate out of this
     * isolated transaction and be caught by a non-transactional caller
     * instead — see {@code ProcessedEventPersistenceAdapter}'s own javadoc.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    default ProcessedEventJpaEntity insertIsolated(ProcessedEventJpaEntity entity) {
        return saveAndFlush(entity);
    }
}
