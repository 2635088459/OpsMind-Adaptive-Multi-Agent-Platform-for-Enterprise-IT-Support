package com.opsmind.identity.application.port.out;

import com.opsmind.identity.application.model.OutboxEventRecord;

import java.time.Instant;
import java.util.List;

/** 13-package-and-class-design §Output Ports; 08-transaction-and-outbox. */
public interface OutboxEventRepository {

    /** Durable append in the caller's own transaction (INV: state + audit + outbox commit together). */
    void append(OutboxEventRecord record);

    /** Rows due for a dispatch attempt, oldest first, up to {@code limit}. */
    List<OutboxEventRecord> findPendingBatch(Instant now, int limit);

    void markPublished(String outboxId, Instant publishedAt);

    void markRetry(String outboxId, int attemptCount, Instant nextAvailableAt);

    void markFailed(String outboxId);

    long countPending();
}
