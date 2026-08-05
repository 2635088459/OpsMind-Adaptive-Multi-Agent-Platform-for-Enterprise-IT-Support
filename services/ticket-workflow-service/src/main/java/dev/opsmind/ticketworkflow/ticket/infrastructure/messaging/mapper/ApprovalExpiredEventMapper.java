package dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.mapper;

import dev.opsmind.ticketworkflow.ticket.application.command.ApplyApprovalExpiredCommand;
import dev.opsmind.ticketworkflow.ticket.application.exception.ConsumedEventSchemaInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ApprovalExpiredEventPayload;
import dev.opsmind.ticketworkflow.ticket.infrastructure.messaging.contract.ConsumedEventEnvelope;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ApprovalExpiredEventMapper {

    /** CON-008 carries no reason field; the trusted {@code approval.expired.v1} event is always the Approval Service's own timeout classification. */
    private static final String EXPIRATION_REASON = "APPROVAL_SERVICE_TIMEOUT";

    public ApplyApprovalExpiredCommand toCommand(ConsumedEventEnvelope envelope, ApprovalExpiredEventPayload payload) {
        TicketId ticketId;
        try {
            ticketId = TicketId.of(UUID.fromString(envelope.ticketId()));
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException(envelope.eventType(), "envelope ticketId is not a valid UUID");
        }

        return new ApplyApprovalExpiredCommand(
            ticketId,
            envelope.eventId(),
            payload.workflowId(),
            payload.actionId(),
            payload.approvalId(),
            payload.expiredAt(),
            EXPIRATION_REASON,
            envelope.traceId(),
            envelope.correlationId()
        );
    }
}
