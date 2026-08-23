package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.shared.DomainEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SPEC-PG-013: the real {@code approval.granted.v1} event
 * (06-event-contracts §Published Events: "Published after approval is
 * granted. 05/03/02 depend on this event to resume waiting states."),
 * replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder for this one aggregate action — mirroring how {@link
 * ApprovalRequestedEvent} graduated {@code approval.requested.v1} in
 * SPEC-PG-010 and {@link ApprovalCancelledEvent}/{@link ApprovalExpiredEvent}
 * graduated {@code approval.cancelled.v1}/{@code approval.expired.v1} in
 * SPEC-PG-012. Carries the {@link ApprovalDecision}'s own {@code
 * conditions}/{@code separationOfDutiesCheck} in addition to the
 * approvalRequestId/sourceDomain/sourceRequestId/requestHash
 * 06-event-contracts §Idempotency requires on every approval decision event —
 * a downstream consumer resuming a waiting state (e.g. 05 Tool Gateway
 * executing a tool) needs the granted conditions to enforce, not just the
 * bare outcome.
 */
public record ApprovalGrantedEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String approvalRequestId,
    String ticketId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "approval.granted.v1";

    public ApprovalGrantedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        // workflowInstanceId/toolRequestId/policyDecisionId are legitimately
        // absent on a given ApprovalRequest — Map.of would reject the null
        // values, so use an unmodifiable wrapper instead.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }

    public static ApprovalGrantedEvent from(ApprovalRequest request, ApprovalDecision decision, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalRequestId", request.approvalRequestId());
        payload.put("requestKey", request.requestKey());
        payload.put("sourceDomain", request.sourceDomain());
        payload.put("sourceRequestId", request.sourceRequestId());
        payload.put("requestHash", request.requestHash());
        payload.put("workflowInstanceId", request.workflowInstanceId());
        payload.put("toolRequestId", request.toolRequestId());
        payload.put("policyDecisionId", request.policyDecisionId());
        payload.put("decidedBy", decision.decidedBy());
        payload.put("reason", decision.reason());
        payload.put("conditions", conditionsPayload(decision.conditions()));
        payload.put("separationOfDutiesCheck", decision.separationOfDutiesCheck());
        return new ApprovalGrantedEvent(
            UUID.randomUUID().toString(), Instant.now(), correlationId, causationId,
            request.approvalRequestId(), request.ticketId(), payload
        );
    }

    private static List<Map<String, String>> conditionsPayload(List<com.opsmind.policygovernance.domain.decision.Constraint> conditions) {
        return conditions.stream()
            .map(c -> Map.of("type", c.type().name(), "detail", c.detail()))
            .toList();
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
