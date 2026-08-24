package com.opsmind.policygovernance.domain.policy;

import com.opsmind.policygovernance.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-PG-020: the real {@code policy.published.v1} event
 * (06-event-contracts §Published Events: "Published after a new policy
 * version is published."), replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder for this one aggregate action — the same graduation
 * {@code domain.approval.ApprovalRequestedEvent} (SPEC-PG-010) and its
 * siblings already went through. This is also the cache-invalidation signal
 * SPEC-PG-021 (Policy Cache Refresh Contract) names by name: 05/03/04 each
 * maintain their own local policy cache and refresh it on this event rather
 * than polling — the payload carries exactly what a consumer needs to
 * invalidate a stale entry (policyId/versionNumber) without a follow-up
 * call.
 */
public record PolicyPublishedEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String policyVersionId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "policy.published.v1";

    public PolicyPublishedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(policyVersionId, "policyVersionId");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }

    public static PolicyPublishedEvent from(PolicyVersion version, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyVersionId", version.policyVersionId());
        payload.put("policyId", version.policyId());
        payload.put("versionNumber", version.versionNumber());
        payload.put("publishedBy", version.publishedBy());
        payload.put("effectiveFrom", version.effectiveFrom() == null ? null : version.effectiveFrom().toString());
        return new PolicyPublishedEvent(
            UUID.randomUUID().toString(), Instant.now(), correlationId, causationId, version.policyVersionId(), payload
        );
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String aggregateType() {
        return "PolicyVersion";
    }

    @Override
    public String aggregateId() {
        return policyVersionId;
    }

    @Override
    public String ticketId() {
        return null;
    }
}
