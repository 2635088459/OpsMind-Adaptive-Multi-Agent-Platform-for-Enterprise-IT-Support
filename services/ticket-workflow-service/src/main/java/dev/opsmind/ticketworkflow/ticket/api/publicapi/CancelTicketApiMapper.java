package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CancelTicketApiMapper {

    public CancelTicketCommand toCommand(
        TicketId ticketId, CancelTicketRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt,
        StepUpProof stepUpProof
    ) {
        return new CancelTicketCommand(
            ticketId,
            request.cancelReasonCode(),
            request.cancelReason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt,
            stepUpProof
        );
    }

    public CancelTicketResponse toResponse(CancelTicketResult result) {
        return new CancelTicketResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.cancelReasonCode().name(),
            result.cancelledBy(),
            result.cancelledAt(),
            result.resolutionCycleId(),
            result.version()
        );
    }
}
