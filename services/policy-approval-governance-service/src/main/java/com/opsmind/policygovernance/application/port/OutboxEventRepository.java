package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /** SPEC-PG-024: looks up a single row by id, regardless of status — used by the admin requeue (poison repair) entry point. */
    Optional<OutboxEventRecord> findById(String outboxId);

    /**
     * SPEC-PG-024 (10-failure-handling §Recovery: "reschedule poison
     * review"): resets a dead-lettered row back to {@code PENDING} with a
     * fresh {@code attemptCount} of {@code 0} and the given {@code
     * availableAt} — an admin's fresh retry budget after repairing whatever
     * caused the original dead-lettering, not a partial continuation of the
     * exhausted one.
     */
    void requeue(String outboxId, Instant availableAt);

    /**
     * SPEC-PG-033 (goal: "poison decision review" / "startup recovery
     * workers" — 10-failure-handling §Poison Decision: "outbox publish
     * repeatedly fails"; §Recovery: "reschedule poison review"). Every
     * dead-lettered ({@code FAILED}) row, so an operator can see exactly
     * which ones need {@link #requeue} — a review surface, same as {@code
     * application.port.PolicyDecisionRepository#findEvaluationFailed}, not
     * an automatic bulk fix.
     */
    List<OutboxEventRecord> findFailed();
}
