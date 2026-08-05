package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalRejectedCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ApprovalRejectedEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ApprovalRejectedEventMapper {

    public ApplyApprovalRejectedCommand toCommand(ConsumedEventEnvelope envelope, ApprovalRejectedEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyApprovalRejectedCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.approvalId(),
            payload.rejectedByIdHash(),
            payload.rejectedAt(),
            payload.reasonCode(),
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
