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
 * SPEC-PG-013: the real {@code approval.denied.v1} event
 * (06-event-contracts §Published Events: "Published after approval is
 * denied."), replacing the generic {@code
 * com.opsmind.policygovernance.domain.shared.SimpleGovernanceEvent}
 * placeholder for this one aggregate action — see {@link
 * ApprovalGrantedEvent}'s own javadoc for the graduation lineage this
 * mirrors. Unlike {@link ApprovalGrantedEvent}, no {@code
 * separationOfDutiesCheck} field: {@link ApprovalDecision}'s own constructor
 * only requires that check for an {@code APPROVED} outcome (INV-PG-004), so
 * it is never meaningfully set on a denial.
 */
public record ApprovalDeniedEvent(
    String eventId,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String approvalRequestId,
    String ticketId,
    Map<String, Object> payload
) implements DomainEvent {

    public static final String EVENT_TYPE = "approval.denied.v1";

    public ApprovalDeniedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        // workflowInstanceId/toolRequestId/policyDecisionId are legitimately
        // absent on a given ApprovalRequest — Map.of would reject the null
        // values, so use an unmodifiable wrapper instead.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }

    public static ApprovalDeniedEvent from(ApprovalRequest request, ApprovalDecision decision, String correlationId, String causationId) {
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
        return new ApprovalDeniedEvent(
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
