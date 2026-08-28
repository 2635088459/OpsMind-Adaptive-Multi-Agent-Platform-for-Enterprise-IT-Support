package com.opsmind.identity.application.service;

import com.opsmind.identity.application.port.out.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-UA-028 (06-event-contracts §Idempotency: "Consumers deduplicate by
 * (consumerName, eventId)"). The first real caller of {@code
 * ProcessedEventRepository#markProcessedIfNew} — that port and its
 * real unique-constraint-backed adapter have existed since SPEC-UA-002/003
 * ("schema and behavior now, callers later"). Mirrors
 * policy-approval-governance-service's own {@code
 * ConsumedEventDeduplicationService} — a small, reusable seam any future
 * inbound-event consumer in this domain can share instead of hand-rolling
 * its own dedup check. {@link #ifNew} marks {@code (eventId, consumerName)}
 * processed and, only if that pair was genuinely new, runs {@code action}
 * in the same transaction; a redelivered message is silently a no-op.
 */
@Service
public class ConsumedEventDeduplicationService {

    private final ProcessedEventRepository processedEventRepository;

    public ConsumedEventDeduplicationService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void ifNew(String eventId, String consumerName, String eventType, Runnable action) {
        if (processedEventRepository.markProcessedIfNew(eventId, consumerName, eventType)) {
            action.run();
        }
    }
}
