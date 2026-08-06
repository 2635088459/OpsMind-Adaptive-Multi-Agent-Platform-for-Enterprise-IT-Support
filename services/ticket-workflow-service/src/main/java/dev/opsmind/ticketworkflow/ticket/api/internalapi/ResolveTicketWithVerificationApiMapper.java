package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ResolveTicketWithVerificationApiMapper {

    public ResolveTicketWithVerificationCommand toCommand(
        TicketId ticketId, ResolveTicketWithVerificationRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ResolveTicketWithVerificationCommand(
            ticketId,
            request.verificationEvidenceId().trim(),
            request.resolutionCode(),
            request.resolutionSummary(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public ResolveTicketWithVerificationResponse toResponse(ResolveTicketWithVerificationResult result) {
        return new ResolveTicketWithVerificationResponse(
            result.ticketId().value(),
            result.previousStatus().name(),
            result.status().name(),
            result.verificationId(),
            result.verificationEvidenceId(),
            result.resolutionCode().name(),
            result.resolutionSummary(),
            result.resolvedBy(),
            result.resolvedAt(),
            result.resolutionCycleId(),
            result.autoCloseDueAt(),
            result.version()
        );
    }
}
