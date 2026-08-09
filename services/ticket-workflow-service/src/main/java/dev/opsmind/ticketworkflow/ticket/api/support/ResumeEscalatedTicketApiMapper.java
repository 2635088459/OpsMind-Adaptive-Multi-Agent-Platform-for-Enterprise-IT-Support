package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResumeEscalatedTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ResumeEscalatedTicketApiMapper {

    public ResumeEscalatedTicketCommand toCommand(
        TicketId ticketId, ResumeEscalatedTicketRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ResumeEscalatedTicketCommand(
            ticketId,
            request.resumeReasonCode(),
            request.resumeReason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public ResumeEscalatedTicketResponse toResponse(ResumeEscalatedTicketResult result) {
        return new ResumeEscalatedTicketResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.resumeReasonCode().name(),
            result.resumedBy(),
            result.resumedAt(),
            result.resolutionCycleId(),
            result.ownershipStatus().name(),
            result.version()
        );
    }
}
