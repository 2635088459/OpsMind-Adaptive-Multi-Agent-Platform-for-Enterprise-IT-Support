package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.TicketApprovalRequiredPayload;

import java.util.List;

/**
 * SPEC-PG-027: pure envelope+payload -&gt; {@link RequestApprovalCommand}
 * translation, mirroring {@link ToolApprovalRequiredEventMapper}'s own
 * field-derivation reasoning with {@code ticketId} itself standing in for
 * {@code toolRequestId} as the primary business key — see that type's own
 * javadoc. {@link #resolveApprovalType} is this mapper's one addition over
 * the tool/workflow mappers: giving SPEC-PG-023's three ticket-exception
 * {@link ApprovalType} values their first real caller.
 */
public final class TicketApprovalRequiredEventMapper {

    private TicketApprovalRequiredEventMapper() {
    }

    public static RequestApprovalCommand toCommand(ConsumedEventEnvelope envelope, TicketApprovalRequiredPayload payload) {
        String ticketId = payload.ticketId() != null ? payload.ticketId() : envelope.ticketId();
        requireNonBlank(ticketId, "payload.ticketId");
        requireNonBlank(payload.inputHash(), "payload.inputHash");
        requireNonBlank(payload.riskLevel(), "payload.riskLevel");
        requireNonBlank(envelope.producer(), "producer");
        requireNonBlank(envelope.correlationId(), "correlationId");
        requireNonBlank(envelope.eventId(), "eventId");

        RiskLevel riskLevel = parseRiskLevel(payload.riskLevel());
        ApprovalType approvalType = resolveApprovalType(payload.exceptionType());

        return new RequestApprovalCommand(
            ticketId, payload.inputHash(), envelope.producer(), ticketId,
            ticketId, null, null, null, null,
            envelope.producer(), approvalType, riskLevel,
            payload.constraints() == null ? List.of() : payload.constraints(), payload.expiresAt(),
            envelope.correlationId(), envelope.eventId()
        );
    }

    /**
     * {@code null} (an ordinary ticket action that is none of the three
     * named exceptions) maps to the generic {@code TICKET_ACTION} — a
     * legitimate, unexceptional case, not a schema error. A non-null value
     * that matches none of the three known exception names IS treated as a
     * schema error (the same strictness {@link #parseRiskLevel} already
     * applies) — a producer sending a value we do not recognize is worth
     * surfacing to the DLQ, not silently miscategorizing.
     */
    static ApprovalType resolveApprovalType(String exceptionType) {
        if (exceptionType == null) {
            return ApprovalType.TICKET_ACTION;
        }
        return switch (exceptionType) {
            case "SLA_EXCEPTION" -> ApprovalType.TICKET_SLA_EXCEPTION;
            case "CLOSURE_OVERRIDE" -> ApprovalType.TICKET_CLOSURE_OVERRIDE;
            case "ESCALATION_EXCEPTION" -> ApprovalType.TICKET_ESCALATION_EXCEPTION;
            default -> throw new ConsumedEventSchemaInvalidException("payload.exceptionType is not recognized: " + exceptionType);
        };
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
            throw new ConsumedEventSchemaInvalidException(fieldName + " is required on ticket.approval.required.v1");
        }
    }
}
