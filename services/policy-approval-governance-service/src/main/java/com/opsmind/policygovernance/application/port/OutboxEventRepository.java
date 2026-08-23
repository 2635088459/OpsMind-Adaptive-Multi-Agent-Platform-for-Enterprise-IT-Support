package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;

import java.time.Instant;
import java.util.List;

/** Port for {@code outbox_events} persistence (08-transaction-and-outbox). */
public interface OutboxEventRepository {

    void append(OutboxEventRecord record);

    /** Rows in {@code PENDING} status whose {@code availableAt} has passed, oldest first. */
    List<OutboxEventRecord> findPendingBatch(Instant now, int limit);

    void markPublished(String outboxId, Instant publishedAt);

    void markRetry(String outboxId, int attemptCount, Instant nextAvailableAt);

    void markFailed(String outboxId);

    /** Backs the {@code governance_outbox_pending_count} gauge (12-observability). */
    long countPending();
}
