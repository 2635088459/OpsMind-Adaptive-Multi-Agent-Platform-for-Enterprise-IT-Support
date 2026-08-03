package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalGrantedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ApprovalGrantedEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ApprovalGrantedEventMapper {

    public ApplyApprovalGrantedCommand toCommand(ConsumedEventEnvelope envelope, ApprovalGrantedEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyApprovalGrantedCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.actionType(),
            payload.approvalId(),
            payload.approvedByIdHash(),
            payload.approvedAt(),
            payload.expiresAt(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
