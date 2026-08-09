package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ConfirmResolutionResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ConfirmResolutionApiMapper {

    public ConfirmResolutionCommand toCommand(
        TicketId ticketId, ConfirmResolutionRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ConfirmResolutionCommand(
            ticketId,
            request.reasonCode(),
            request.reason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public ConfirmResolutionResponse toResponse(ConfirmResolutionResult result) {
        return new ConfirmResolutionResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.reasonCode().name(),
            result.confirmedBy(),
            result.confirmedAt(),
            result.resolutionCycleId(),
            result.version()
        );
    }
}
