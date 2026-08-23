package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-PG-012: the real {@code approval.cancelled.v1} event
 * (06-event-contracts §Published Events: "Published after approval is
 * cancelled by requester or governance"), replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder for this one aggregate action — mirroring how {@link
 * ApprovalRequestedEvent} graduated {@code approval.requested.v1} in
 * SPEC-PG-010. Carries {@code cancelledBy}/{@code reason} (unique to a
 * cancel, unlike {@link ApprovalExpiredEvent}) plus the same
 * approvalRequestId/sourceDomain/sourceRequestId/requestHash 06-event-contracts
 * §Idempotency requires on every approval decision event, keeping
 * "cancelled" distinguishable from "expired"/"denied" for a downstream
 * consumer per INV-PG-007.
 */
public record ApprovalCancelledEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String approvalRequestId,
    String ticketId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "approval.cancelled.v1";

    public ApprovalCancelledEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        // workflowInstanceId/toolRequestId/policyDecisionId are legitimately
        // absent on a given ApprovalRequest — Map.of would reject the null
        // values, so use an unmodifiable wrapper instead.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }

    public static ApprovalCancelledEvent from(ApprovalRequest request, String reason, String cancelledBy, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalRequestId", request.approvalRequestId());
        payload.put("requestKey", request.requestKey());
        payload.put("sourceDomain", request.sourceDomain());
        payload.put("sourceRequestId", request.sourceRequestId());
        payload.put("requestHash", request.requestHash());
        payload.put("workflowInstanceId", request.workflowInstanceId());
        payload.put("toolRequestId", request.toolRequestId());
        payload.put("policyDecisionId", request.policyDecisionId());
        payload.put("cancelledBy", cancelledBy);
        payload.put("reason", reason);
        return new ApprovalCancelledEvent(
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
