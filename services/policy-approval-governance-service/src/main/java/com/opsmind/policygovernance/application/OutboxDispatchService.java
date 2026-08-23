package com.opsmind.policygovernance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.application.port.MessageBrokerPublisherPort;
import com.opsmind.policygovernance.application.port.OutboxEventRepository;
import com.opsmind.policygovernance.domain.shared.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The real transactional outbox (SPEC-PG-003, 08-transaction-and-outbox):
 * {@link #stage} durably inserts a {@code PENDING} row in the same
 * transaction as the governance fact it describes, so a crash between
 * commit and broker publish can always be recovered by replaying {@link
 * #publishPending}. That method is deliberately never invoked by a {@code
 * @Scheduled} trigger or constructed as a running background process in
 * this codebase — mirrors ticket-workflow-service's own {@code
 * OutboxPersistenceAdapter} (append-only, no in-process drain loop either)
 * and tool-integration-gateway's/memory-knowledge-service's worker classes,
 * a repeatedly-confirmed platform-wide scope boundary, not a gap: draining
 * is exercised directly by tests and is safe to call from an admin
 * endpoint or an external scheduler once one exists. {@link #publishPending}
 * is itself {@code @Transactional} so a row's status update is atomic with
 * its own publish attempt.
 */
@Service
public class OutboxDispatchService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchService.class);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration BACKOFF_UNIT = Duration.ofSeconds(30);
    private static final String PRODUCER = "policy-approval-governance-service";
    private static final int SCHEMA_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;
    private final MessageBrokerPublisherPort messageBrokerPublisher;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OutboxDispatchService(
        OutboxEventRepository outboxEventRepository, MessageBrokerPublisherPort messageBrokerPublisher, Clock clock
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.messageBrokerPublisher = messageBrokerPublisher;
        this.clock = clock;
    }

    @Transactional
    public void stage(DomainEvent event) {
        outboxEventRepository.append(new OutboxEventRecord(
            event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(), "v1",
            buildPayload(event), event.correlationId(), event.causationId(),
            OutboxEventStatus.PENDING, 0, clock.instant(), null, event.occurredAt()
        ));
    }

    /** Drains up to {@link #DEFAULT_BATCH_SIZE} due rows. See the class javadoc for why nothing calls this automatically. */
    @Transactional
    public DrainResult publishPending() {
        return publishPending(DEFAULT_BATCH_SIZE);
    }

    @Transactional
    public DrainResult publishPending(int batchSize) {
        List<OutboxEventRecord> due = outboxEventRepository.findPendingBatch(clock.instant(), batchSize);
        int published = 0;
        int retried = 0;
        int deadLettered = 0;
        for (OutboxEventRecord record : due) {
            try {
                messageBrokerPublisher.publish(record);
                outboxEventRepository.markPublished(record.outboxId(), clock.instant());
                published++;
            } catch (Exception e) {
                int nextAttempt = record.attemptCount() + 1;
                if (nextAttempt >= MAX_ATTEMPTS) {
                    log.error("outbox event {} exhausted {} attempts, dead-lettering: {}", record.outboxId(), nextAttempt, e.getMessage());
                    outboxEventRepository.markFailed(record.outboxId());
                    deadLettered++;
                } else {
                    log.warn("outbox event {} publish attempt {} failed, retrying: {}", record.outboxId(), nextAttempt, e.getMessage());
                    outboxEventRepository.markRetry(record.outboxId(), nextAttempt, clock.instant().plus(BACKOFF_UNIT.multipliedBy(nextAttempt)));
                    retried++;
                }
            }
        }
        return new DrainResult(published, retried, deadLettered);
    }

    /**
     * The shared envelope 06-event-contracts §Envelope defines for every
     * published event (eventId/eventType/producer/schemaVersion/aggregateId/
     * ticketId/correlationId/causationId/occurredAt/payload) — built the
     * same way regardless of which event is being staged, per that
     * section's own wording ("All events use the shared envelope"). {@code
     * payload} itself is business-specific and supplied by the event (empty
     * for events still on {@code SimpleGovernanceEvent}).
     */
    private String buildPayload(DomainEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", event.eventType());
        envelope.put("producer", PRODUCER);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("aggregateId", event.aggregateId());
        if (event.ticketId() != null) {
            envelope.put("ticketId", event.ticketId());
        }
        envelope.put("correlationId", event.correlationId());
        if (event.causationId() != null) {
            envelope.put("causationId", event.causationId());
        }
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("payload", event.payload());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outbox payload for event " + event.eventId(), e);
        }
    }

    public record DrainResult(int published, int retried, int deadLettered) {
    }
}
