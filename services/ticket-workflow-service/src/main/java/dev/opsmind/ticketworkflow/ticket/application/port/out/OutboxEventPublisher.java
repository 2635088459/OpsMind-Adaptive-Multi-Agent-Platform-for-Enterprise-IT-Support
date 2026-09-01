package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;

/**
 * Project-level integration verification (2026-09-01): the real broker
 * publish side of the outbox pattern — see {@code
 * OutboxDispatchApplicationService}'s own javadoc for why this was never
 * built until now. Throws on any failure (network, broker down, etc.); the
 * caller is responsible for the retry/backoff bookkeeping, not this port.
 */
public interface OutboxEventPublisher {

    void publish(OutboxEventEntry entry);
}
