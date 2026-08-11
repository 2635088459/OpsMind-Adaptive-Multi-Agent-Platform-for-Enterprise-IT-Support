package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ExecuteCompensationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ExecuteCompensationResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExecuteCompensationApiMapper {

    public ExecuteCompensationCommand toCommand(
        TicketId ticketId, ExecuteCompensationRequest request, ActorContext actor,
        String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ExecuteCompensationCommand(
            ticketId,
            request.compensationAction(),
            request.reasonCode(),
            request.reason().trim(),
            request.sourceReference().trim(),
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public ExecuteCompensationResponse toResponse(ExecuteCompensationResult result) {
        return new ExecuteCompensationResponse(
            result.decision().name(),
            result.recoveryId(),
            result.eventName()
        );
    }
}
