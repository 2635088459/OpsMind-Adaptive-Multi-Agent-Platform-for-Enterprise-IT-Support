package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UpdateTicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class UpdateTicketAssignmentApiMapper {

    public UpdateTicketAssignmentCommand toCommand(
        TicketId ticketId, UpdateTicketAssignmentRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new UpdateTicketAssignmentCommand(
            ticketId,
            SupportQueueId.of(request.supportQueueId()),
            request.assigneeId(),
            request.reason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public UpdateTicketAssignmentResponse toResponse(UpdateTicketAssignmentResult result) {
        return new UpdateTicketAssignmentResponse(
            result.ticketId().value(),
            result.status().name(),
            result.teamId(),
            result.supportQueueId().value(),
            result.assigneeId(),
            result.assigneeDisplayName(),
            result.reason(),
            result.updatedBy(),
            result.updatedAt(),
            result.version()
        );
    }
}
