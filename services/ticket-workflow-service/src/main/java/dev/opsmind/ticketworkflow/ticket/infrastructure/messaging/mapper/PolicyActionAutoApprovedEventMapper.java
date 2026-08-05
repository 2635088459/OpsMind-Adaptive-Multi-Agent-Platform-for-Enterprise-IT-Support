package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyAutoApprovedPolicyCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.PolicyActionAutoApprovedEventPayload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PolicyActionAutoApprovedEventMapper {

    public ApplyAutoApprovedPolicyCommand toCommand(ConsumedEventEnvelope envelope, PolicyActionAutoApprovedEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        ApprovalRiskLevel riskLevel;
        try {
            riskLevel = ApprovalRiskLevel.valueOf(payload.riskLevel());
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "unrecognized riskLevel: " + payload.riskLevel());
        }

        return new ApplyAutoApprovedPolicyCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.actionType(),
            riskLevel,
            payload.policyId(),
            payload.policyVersion(),
            payload.policyDecisionId(),
            payload.decidedAt(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
