package com.opsmind.policygovernance.application.port;

import com.opsmind.policygovernance.application.model.ProcessedEventRecord;

import java.util.List;

/**
 * Port for {@code processed_events} persistence (06-event-contracts
 * §Idempotency: "Every consumer deduplicates by {@code eventId +
 * consumerName}"). SPEC-PG-025 is this port's first real caller — the
 * {@code processed_events} table itself has existed since SPEC-PG-003's
 * schema baseline, but nothing in the application ever wrote to it before
 * this spec built 06's first real inbound event consumer.
 */
public interface ProcessedEventRepository {

    /**
     * Atomically records {@code (eventId, consumerName)} as processed and
     * returns {@code true} — unless that exact pair was already recorded,
     * in which case it returns {@code false} and leaves the existing row
     * untouched. Relies on the same {@code UNIQUE (event_id, consumer_name)}
     * database constraint 06-event-contracts names, not an
     * exists-then-insert check, so two concurrent deliveries of the same
     * message race safely: only one ever sees {@code true}.
     */
    boolean markProcessedIfNew(String eventId, String consumerName, String eventType);

    /**
     * SPEC-PG-034 (goal: "admin-safe repair flow for governance event
     * replay/backfill"): every {@code (consumerName, processedAt)} pair
     * recorded for this {@code eventId}, across every consumer — an
     * operator's own review surface before deciding whether a backfill is
     * actually needed.
     */
    List<ProcessedEventRecord> findByEventId(String eventId);

    /**
     * SPEC-PG-034: deletes the {@code (eventId, consumerName)} dedup marker
     * if one exists, returning whether a row was actually removed. This is
     * the one deliberate, admin-triggered way to make {@link
     * #markProcessedIfNew} treat a redelivered event as new again — 06 has
     * no way to force a redelivery itself (RabbitMQ owns that), so this is
     * the cooperating half: clearing 06's own dedup ledger first, so that
     * whatever redelivers the message (an upstream domain's own replay/
     * backfill) is not silently absorbed as a no-op here.
     */
    boolean deleteIfExists(String eventId, String consumerName);
}
