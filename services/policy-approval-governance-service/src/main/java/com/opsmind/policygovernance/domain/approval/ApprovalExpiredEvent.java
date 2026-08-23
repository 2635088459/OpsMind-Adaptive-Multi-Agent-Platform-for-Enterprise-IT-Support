package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-PG-012: the real {@code approval.expired.v1} event
 * (06-event-contracts §Published Events: "Published after approval times
 * out"; 10-failure-handling §Approval Timeout: "Expiry worker moves it to
 * EXPIRED; publishes approval.expired.v1"), replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder for this one aggregate action — mirroring how {@link
 * ApprovalRequestedEvent} graduated {@code approval.requested.v1} in
 * SPEC-PG-010. Per 06-event-contracts §Idempotency ("approval decision
 * events must also include approvalRequestId, sourceDomain, sourceRequestId,
 * and requestHash"), the payload carries all four so a downstream consumer
 * can deduplicate and re-link without a follow-up call, and keeps this
 * event's own fields distinguishable from {@code approval.denied.v1}/{@code
 * approval.cancelled.v1} per INV-PG-007 (expired/denied/cancelled must never
 * collapse into a generic "denied").
 */
public record ApprovalExpiredEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String approvalRequestId,
    String ticketId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "approval.expired.v1";

    public ApprovalExpiredEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        // workflowInstanceId/toolRequestId/policyDecisionId/expiresAt are
        // legitimately absent on a given ApprovalRequest — Map.of would
        // reject the null values, so use an unmodifiable wrapper instead.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }

    public static ApprovalExpiredEvent from(ApprovalRequest request, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalRequestId", request.approvalRequestId());
        payload.put("requestKey", request.requestKey());
        payload.put("sourceDomain", request.sourceDomain());
        payload.put("sourceRequestId", request.sourceRequestId());
        payload.put("requestHash", request.requestHash());
        payload.put("workflowInstanceId", request.workflowInstanceId());
        payload.put("toolRequestId", request.toolRequestId());
        payload.put("policyDecisionId", request.policyDecisionId());
        payload.put("expiresAt", request.expiresAt() == null ? null : request.expiresAt().toString());
        return new ApprovalExpiredEvent(
            UUID.randomUUID().toString(), Instant.now(), correlationId, causationId,
            request.approvalRequestId(), request.ticketId(), payload
        );
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String aggregateType() {
        return "ApprovalRequest";
    }

    @Override
    public String aggregateId() {
        return approvalRequestId;
    }
}
