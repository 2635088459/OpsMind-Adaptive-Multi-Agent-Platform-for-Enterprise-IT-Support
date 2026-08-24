package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.port.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * SPEC-PG-025 (06-event-contracts §Idempotency: "Every consumer deduplicates
 * by {@code eventId + consumerName}"). A small, reusable seam every future
 * inbound-event consumer (workflow/ticket/memory, phase-06's remaining
 * specs) can share instead of each hand-rolling its own dedup check —
 * {@link #ifNew} marks {@code (eventId, consumerName)} processed and, only
 * if that pair was genuinely new, runs {@code action} in the same
 * transaction. A redelivered message (same {@code eventId}) is silently a
 * no-op: 06-event-contracts does not ask for a "duplicate rejected" signal
 * back to the broker, only that reprocessing does not create a second
 * governance fact — the same idempotent-consumer contract {@code
 * ApprovalService#request}'s own {@code requestKey} dedup already models at
 * the business-key level.
 */
@Service
public class ConsumedEventDeduplicationService {

    private final ProcessedEventRepository processedEventRepository;

    public ConsumedEventDeduplicationService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Returns {@code action.get()} if {@code (eventId, consumerName)} was
     * genuinely new, or {@code alreadyProcessedResult} (typically {@code
     * null}, meaning "nothing to return, this was a no-op") if it had
     * already been recorded.
     */
    @Transactional
    public <T> T ifNew(String eventId, String consumerName, String eventType, Supplier<T> action, T alreadyProcessedResult) {
        return processedEventRepository.markProcessedIfNew(eventId, consumerName, eventType) ? action.get() : alreadyProcessedResult;
    }
}
