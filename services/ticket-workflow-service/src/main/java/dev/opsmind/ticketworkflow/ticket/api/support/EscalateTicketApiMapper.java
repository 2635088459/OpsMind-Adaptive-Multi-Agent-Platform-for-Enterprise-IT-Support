package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EscalateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EscalateTicketApiMapper {

    public EscalateTicketCommand toCommand(
        TicketId ticketId, EscalateTicketRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt,
        StepUpProof stepUpProof
    ) {
        return new EscalateTicketCommand(
            ticketId,
            request.escalationReasonCode(),
            request.escalationReason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt,
            stepUpProof
        );
    }

    public EscalateTicketResponse toResponse(EscalateTicketResult result) {
        return new EscalateTicketResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.escalationReasonCode().name(),
            result.escalatedBy(),
            result.escalatedAt(),
            result.resolutionCycleId(),
            result.version()
        );
    }
}
