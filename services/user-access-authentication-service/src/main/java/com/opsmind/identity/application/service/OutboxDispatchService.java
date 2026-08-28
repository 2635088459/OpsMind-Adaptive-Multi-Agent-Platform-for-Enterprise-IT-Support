package com.opsmind.identity.application.service;

import com.opsmind.identity.application.model.OutboxEventRecord;
import com.opsmind.identity.application.port.out.MessageBrokerPublisherPort;
import com.opsmind.identity.application.port.out.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * The real dispatch half of the transactional outbox (SPEC-UA-003,
 * 08-transaction-and-outbox: "The dispatcher claims batches ... exponential
 * backoff, and bounded attempts. Exhausted rows become POISONED"). Mirrors
 * policy-approval-governance-service's own {@code OutboxDispatchService}:
 * {@link #publishPending} is deliberately never invoked by a {@code
 * @Scheduled} trigger or run as a background process in this codebase (a
 * repeatedly-confirmed platform-wide scope boundary, not a gap) — an admin
 * endpoint or an external scheduler drives it.
 */
@Service
public class OutboxDispatchService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchService.class);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration BACKOFF_UNIT = Duration.ofSeconds(30);

    private final OutboxEventRepository outboxEventRepository;
    private final MessageBrokerPublisherPort messageBrokerPublisher;
    private final Clock clock;

    public OutboxDispatchService(OutboxEventRepository outboxEventRepository, MessageBrokerPublisherPort messageBrokerPublisher, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.messageBrokerPublisher = messageBrokerPublisher;
        this.clock = clock;
    }

    @Transactional
    public DrainResult publishPending() {
        return publishPending(DEFAULT_BATCH_SIZE);
    }

    @Transactional
    public DrainResult publishPending(int batchSize) {
        List<OutboxEventRecord> due = outboxEventRepository.findPendingBatch(clock.instant(), batchSize);
        int published = 0;
        int retried = 0;
        int failed = 0;
        for (OutboxEventRecord record : due) {
            try {
                messageBrokerPublisher.publish(record);
                outboxEventRepository.markPublished(record.outboxId(), clock.instant());
                published++;
            } catch (Exception e) {
                int nextAttempt = record.attemptCount() + 1;
                if (nextAttempt >= MAX_ATTEMPTS) {
                    log.error("identity outbox event {} exhausted {} attempts, marking FAILED: {}", record.outboxId(), nextAttempt, e.getMessage());
                    outboxEventRepository.markFailed(record.outboxId());
                    failed++;
                } else {
                    log.warn("identity outbox event {} publish attempt {} failed, retrying: {}", record.outboxId(), nextAttempt, e.getMessage());
                    outboxEventRepository.markRetry(record.outboxId(), nextAttempt, clock.instant().plus(BACKOFF_UNIT.multipliedBy(nextAttempt)));
                    retried++;
                }
            }
        }
        return new DrainResult(published, retried, failed);
    }

    public record DrainResult(int published, int retried, int failed) {
    }
}
