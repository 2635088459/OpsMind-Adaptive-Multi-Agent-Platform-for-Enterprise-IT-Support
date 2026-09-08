package dev.opsmind.ticketworkflow.ticket.infrastructure.sse;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;

/**
 * Published once per {@code OutboxEventRepository.append} call (see {@code
 * OutboxPersistenceAdapter}), in the same transaction as the real ticket
 * state change it records -- every mutating ticket application service
 * (triage, assign, transition, resolve, close, reopen, escalate, ...)
 * already funnels through that one method, so this is a single real choke
 * point rather than a new hook duplicated across each of them.
 * {@link TicketEventBroadcaster} only acts on it {@code AFTER_COMMIT}, so a
 * mutation that rolls back downstream of the outbox append never fans out a
 * false "ticket changed" signal to a live subscriber.
 *
 * Deliberately carries only what a subscriber needs to know "something
 * happened, go re-fetch" -- never the outbox row's own payload, which may
 * hold fields the SSE caller's own view (Employee vs. Support) is not
 * authorized to see. {@code GET /api/v1/tickets/{id}} remains the one place
 * that renders an actor-scoped projection (SPEC-TW-002 §12/§13); this event
 * is not a second one.
 */
public record TicketOutboxEventAppended(TicketId ticketId, String eventType, Instant occurredAt) {
}
