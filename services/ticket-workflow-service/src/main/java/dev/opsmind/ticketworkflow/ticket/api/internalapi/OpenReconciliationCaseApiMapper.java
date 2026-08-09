package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.OpenReconciliationCaseResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OpenReconciliationCaseApiMapper {

    public OpenReconciliationCaseCommand toCommand(
        TicketId ticketId, OpenReconciliationCaseRequest request, ActorContext actor,
        String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new OpenReconciliationCaseCommand(
            ticketId,
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

    public OpenReconciliationCaseResponse toResponse(OpenReconciliationCaseResult result) {
        return new OpenReconciliationCaseResponse(
            result.decision().name(),
            result.recoveryId(),
            result.eventName()
        );
    }
}
