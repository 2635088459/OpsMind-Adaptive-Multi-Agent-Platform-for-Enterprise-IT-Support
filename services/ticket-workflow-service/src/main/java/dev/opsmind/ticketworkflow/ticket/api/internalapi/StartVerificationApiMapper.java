package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.StartVerificationResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class StartVerificationApiMapper {

    public StartVerificationCommand toCommand(
        TicketId ticketId, StartVerificationRequest request, ActorContext actor,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new StartVerificationCommand(
            ticketId,
            request.toolResultId().trim(),
            request.verificationType().trim(),
            request.reason() == null ? null : request.reason().trim(),
            expectedVersion,
            actor,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public StartVerificationResponse toResponse(StartVerificationResult result) {
        return new StartVerificationResponse(
            result.ticketId().value(),
            result.verificationId(),
            result.resolutionCycleId(),
            result.workflowId(),
            result.toolResultId(),
            result.attemptNumber(),
            result.verificationType(),
            result.previousStatus().name(),
            result.status().name(),
            result.startedBy(),
            result.startedAt(),
            result.version()
        );
    }
}
