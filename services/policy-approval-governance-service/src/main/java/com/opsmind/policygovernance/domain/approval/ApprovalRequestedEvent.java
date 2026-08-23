package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-PG-010: the real {@code approval.requested.v1} event
 * (06-event-contracts §Published Events: "Published after ApprovalRequest
 * is created"), replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder for this one aggregate action. {@code aggregateType}/{@code
 * aggregateId} identify the {@link ApprovalRequest} itself (not a
 * self-referencing placeholder); {@code payload} carries the fields a
 * consumer needs to act on the request without a follow-up call.
 */
public record ApprovalRequestedEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String approvalRequestId,
    String ticketId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "approval.requested.v1";

    public ApprovalRequestedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        // Map.copyOf/Map.of reject null values, but several payload fields
        // below (workflowInstanceId, toolRequestId, policyDecisionId,
        // expiresAt) are legitimately absent on a given ApprovalRequest —
        // Collections.unmodifiableMap tolerates them.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }

    public static ApprovalRequestedEvent from(ApprovalRequest request, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalRequestId", request.approvalRequestId());
        payload.put("requestKey", request.requestKey());
        payload.put("sourceDomain", request.sourceDomain());
        payload.put("sourceRequestId", request.sourceRequestId());
        payload.put("workflowInstanceId", request.workflowInstanceId());
        payload.put("toolRequestId", request.toolRequestId());
        payload.put("policyDecisionId", request.policyDecisionId());
        payload.put("requestedBy", request.requestedBy());
        payload.put("approvalType", request.approvalType().name());
        payload.put("riskLevel", request.riskLevel().name());
        payload.put("expiresAt", request.expiresAt() == null ? null : request.expiresAt().toString());
        return new ApprovalRequestedEvent(
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
