package com.opsmind.policygovernance.domain.shared;

import java.time.Instant;
import java.util.Map;

/**
 * A governance fact this service is allowed to publish (policy decisions,
 * approval lifecycle transitions, policy publication). Concrete event types
 * are introduced alongside the spec that owns their payload (see
 * {@code docs/low-level-design/domains/06-policy-approval-governance/06-event-contracts}).
 *
 * <p>{@code correlationId}/{@code causationId} are mandatory so every event
 * stays traceable to the source domain request that triggered it — event
 * contract requirement "Events must carry source linkage, correlationId, and
 * causationId." Durable, transactional publication through {@code
 * outbox_events} is owned by SPEC-PG-003 (Governance Outbox Idempotency Audit
 * Baseline); this interface defines the stable seam application services
 * depend on today.
 *
 * <p>{@code aggregateType}/{@code aggregateId}/{@code ticketId}/{@code
 * payload} were added by SPEC-PG-010: 06-event-contracts' own "Envelope"
 * section names all four as part of the shared envelope every published
 * event uses, and {@code outbox_events} (07-data-model) already has first-class
 * {@code aggregate_type}/{@code aggregate_id} columns that
 * {@code OutboxDispatchService#stage} was populating with a placeholder
 * before this spec ({@code "Governance"} / the event's own id) rather than
 * the real aggregate the event describes. {@code ticketId} is nullable
 * (not every governance fact is ticket-scoped); {@code payload} is an empty
 * map for events that still use {@link SimpleGovernanceEvent} — an honest
 * "no real business payload yet" rather than a fabricated one.
 */
public interface DomainEvent {

    String eventId();

    String eventType();

    Instant occurredAt();

    String correlationId();

    String causationId();

    String aggregateType();

    String aggregateId();

    String ticketId();

    Map<String, Object> payload();
}
