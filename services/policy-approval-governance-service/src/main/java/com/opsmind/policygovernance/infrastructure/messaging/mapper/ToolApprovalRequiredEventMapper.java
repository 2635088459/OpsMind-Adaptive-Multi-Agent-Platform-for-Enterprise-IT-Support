package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ToolApprovalRequiredPayload;

import java.util.List;

/**
 * SPEC-PG-025: pure envelope+payload -&gt; {@link RequestApprovalCommand}
 * translation, kept separate from {@code ToolApprovalRequiredEventConsumer}
 * so the mapping itself is unit-testable without Spring or RabbitMQ
 * (mirrors ticket-workflow-service's own per-event-type mapper classes).
 *
 * <p>Field derivation, since 06-event-contracts names no dedicated
 * request-creation shape for this event beyond its own "Key fields":
 * <ul>
 *   <li>{@code requestKey}/{@code sourceRequestId}/{@code toolRequestId} =
 *       {@code payload.toolRequestId} — one approval request per tool
 *       request, the same way a synchronous caller supplying a natural
 *       business key would.</li>
 *   <li>{@code requestHash} = {@code payload.inputHash} — the payload's own
 *       hash of the thing being approved, the same role {@code inputHash}
 *       plays on {@code PolicyDecision}.</li>
 *   <li>{@code sourceDomain}/{@code requestedBy} = {@code envelope.producer}
 *       — no human actor is named on this event; the producing service is
 *       the only honest "who requested this" 06 was given (INV-PG-001: 06
 *       must not fabricate an actor identity it was never handed).</li>
 *   <li>{@code causationId} = {@code envelope.eventId} — this consumed
 *       event is what causes the resulting {@code approval.requested.v1}.</li>
 * </ul>
 */
public final class ToolApprovalRequiredEventMapper {

    private ToolApprovalRequiredEventMapper() {
    }

    public static RequestApprovalCommand toCommand(ConsumedEventEnvelope envelope, ToolApprovalRequiredPayload payload) {
        requireNonBlank(payload.toolRequestId(), "payload.toolRequestId");
        requireNonBlank(payload.inputHash(), "payload.inputHash");
        requireNonBlank(payload.riskLevel(), "payload.riskLevel");
        requireNonBlank(envelope.producer(), "producer");
        requireNonBlank(envelope.correlationId(), "correlationId");
        requireNonBlank(envelope.eventId(), "eventId");

        RiskLevel riskLevel = parseRiskLevel(payload.riskLevel());
        String ticketId = payload.ticketId() != null ? payload.ticketId() : envelope.ticketId();

        return new RequestApprovalCommand(
            payload.toolRequestId(), payload.inputHash(), envelope.producer(), payload.toolRequestId(),
            ticketId, payload.workflowInstanceId(), payload.toolRequestId(), null, null,
            envelope.producer(), ApprovalType.TOOL_EXECUTION, riskLevel,
            payload.constraints() == null ? List.of() : payload.constraints(), payload.expiresAt(),
            envelope.correlationId(), envelope.eventId()
        );
    }

    private static RiskLevel parseRiskLevel(String value) {
        try {
            return RiskLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException("payload.riskLevel is not a recognized RiskLevel: " + value, e);
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ConsumedEventSchemaInvalidException(fieldName + " is required on tool.approval.required.v1");
        }
    }
}
