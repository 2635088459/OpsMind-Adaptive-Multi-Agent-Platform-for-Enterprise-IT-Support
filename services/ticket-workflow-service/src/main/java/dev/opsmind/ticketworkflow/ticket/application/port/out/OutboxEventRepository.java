package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository {

    void append(OutboxEventEntry entry);

    /**
     * Project-level integration verification (2026-09-01): the real
     * dispatch side of the outbox pattern, previously entirely missing
     * from this service (the write side via {@link #append} was always
     * solid; nothing ever drained a row back out to the broker — see
     * {@code OutboxDispatchApplicationService}'s own javadoc). Claims up
     * to {@code batchSize} due, unlocked-or-stale-locked rows via a real
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}, so concurrent dispatch
     * calls (two admin requests, or a future second instance of this
     * service) never double-publish the same row — the {@code locked_by}/
     * {@code locked_at} columns exist in the schema precisely for this and
     * were never read or written by any code until now.
     */
    List<OutboxEventEntry> claimPublishable(Instant now, Instant staleLockThreshold, String workerId, int batchSize);

    /** Marks a claimed row published and releases its lock. */
    void markPublished(UUID outboxId, Instant publishedAt);

    /** Publish failed: releases the lock, records the attempt/error, and reschedules {@code available_at}. */
    void markRetry(UUID outboxId, int attempts, Instant nextAvailableAt, String errorCode);
}
