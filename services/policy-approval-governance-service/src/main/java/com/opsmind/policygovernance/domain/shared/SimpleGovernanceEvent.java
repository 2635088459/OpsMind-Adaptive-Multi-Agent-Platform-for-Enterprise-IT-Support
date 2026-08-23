package com.opsmind.policygovernance.domain.shared;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A generic {@link DomainEvent} used to prove the outbox seam
 * (application.OutboxDispatchService / application.port.GovernanceEventPublisherPort)
 * before the richer, downstream-addressable event payloads exist.
 *
 * <p>The concrete, versioned event contracts consumed by other domains —
 * {@code policy.decision.created.v1}, {@code approval.requested.v1},
 * {@code approval.granted.v1}, {@code policy.published.v1}, etc. (see
 * 06-event-contracts) — are introduced by the specs that own each aggregate
 * action. {@code approval.requested.v1} graduated to a real event as of
 * SPEC-PG-010 (see {@code domain.approval.ApprovalRequestedEvent}); {@code
 * policy.decision.created.v1}/{@code approval.granted.v1}/{@code
 * approval.denied.v1}/{@code approval.cancelled.v1}/{@code
 * approval.expired.v1}/{@code policy.published.v1} do not yet have a real
 * owner spec (SPEC-PG-013 names approval decision events; nothing in the
 * current roadmap yet names the policy-side ones) and still use this
 * placeholder. Until each graduates, this type lets every governance state
 * transition still satisfy the SPEC-PG-001 domain rule "every governance
 * state transition must write audit/outbox in the same transaction" without
 * pre-committing to payload shapes those future specs must own — {@code
 * aggregateType}/{@code aggregateId} default to a generic self-reference
 * (not the real aggregate) and {@code payload} is empty, both honest
 * placeholders rather than fabricated data.
 */
public record SimpleGovernanceEvent(
    String eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String aggregateType,
    String aggregateId
) implements DomainEvent {

    public SimpleGovernanceEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
    }

    public static SimpleGovernanceEvent of(String eventType, String correlationId, String causationId) {
        String id = UUID.randomUUID().toString();
        return new SimpleGovernanceEvent(id, eventType, Instant.now(), correlationId, causationId, "Governance", id);
    }

    @Override
    public String ticketId() {
        return null;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of();
    }
}
