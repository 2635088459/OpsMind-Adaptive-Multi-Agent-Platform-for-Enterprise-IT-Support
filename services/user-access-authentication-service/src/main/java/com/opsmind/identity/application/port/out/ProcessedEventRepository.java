package com.opsmind.identity.application.port.out;

/**
 * 13-package-and-class-design §Output Ports; 09-concurrency-and-idempotency:
 * "Events deduplicate by (consumer, eventId)." No consumer exists yet in
 * this codebase (domain 01 emits identity facts; SPEC-UA-025+ name the
 * consumers of cross-domain events, not domain 01 as a consumer) — this
 * port and its real unique-constraint-backed adapter exist now so a future
 * consumer only has to call it, the same "schema and behavior now, callers
 * later" split SPEC-PG-002/003 used for {@code processed_events}.
 */
public interface ProcessedEventRepository {

    /** {@code true} if this is the first time {@code (eventId, consumerName)} was seen — relies on a real unique constraint, not check-then-insert. */
    boolean markProcessedIfNew(String eventId, String consumerName, String eventType);
}
