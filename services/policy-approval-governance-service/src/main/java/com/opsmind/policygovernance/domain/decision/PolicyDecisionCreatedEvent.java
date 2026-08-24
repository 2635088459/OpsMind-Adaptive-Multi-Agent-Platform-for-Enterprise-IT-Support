package com.opsmind.policygovernance.domain.decision;

import com.opsmind.policygovernance.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-PG-028: the real {@code policy.decision.created.v1} event
 * (06-event-contracts §Published Events: "Published after PolicyDecision is
 * persisted"), replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder {@code PolicyDecisionService#evaluate} had staged since
 * SPEC-PG-005/006 — the last governance fact in this service still on the
 * placeholder. {@code aggregateType}/{@code aggregateId} identify the
 * {@link PolicyDecision} itself; {@code payload} carries the fields an
 * asynchronous caller (04 Memory Knowledge's own {@code
 * policy.evaluation.requested.v1} request, SPEC-PG-028's own reason this
 * graduation exists now) needs to act on the result without a follow-up
 * {@code GET /policy-decisions/{id}} call.
 */
public record PolicyDecisionCreatedEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String policyDecisionId,
    String ticketId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "policy.decision.created.v1";

    public PolicyDecisionCreatedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(policyDecisionId, "policyDecisionId");
        payload = Map.copyOf(payload == null ? Map.of() : payload);
    }

    public static PolicyDecisionCreatedEvent from(PolicyDecision decision, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyDecisionId", decision.policyDecisionId());
        payload.put("decisionKey", decision.decisionKey());
        payload.put("sourceDomain", decision.sourceDomain());
        payload.put("sourceRequestId", decision.sourceRequestId());
        payload.put("effect", decision.effect().name());
        payload.put("riskLevel", decision.riskLevel().name());
        payload.put("approvalRequired", decision.approvalRequired());
        payload.put("evaluationFailed", decision.evaluationFailed());
        payload.put("degraded", decision.degraded());
        payload.put("reasonCodes", decision.reasonCodes().stream().map(Enum::name).toList());
        payload.put("policyId", decision.policyId());
        payload.put("policyVersion", decision.policyVersion());
        payload.put("evaluatedAt", decision.evaluatedAt().toString());
        return new PolicyDecisionCreatedEvent(
            UUID.randomUUID().toString(), Instant.now(), correlationId, causationId,
            decision.policyDecisionId(), decision.ticketId(), payload
        );
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String aggregateType() {
        return "PolicyDecision";
    }

    @Override
    public String aggregateId() {
        return policyDecisionId;
    }
}
