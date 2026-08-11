package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairResult;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ApplyDataIntegrityRepairApiMapper {

    public ApplyDataIntegrityRepairCommand toCommand(
        ApplyDataIntegrityRepairRequest request, ActorContext actor,
        String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ApplyDataIntegrityRepairCommand(
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

    public ApplyDataIntegrityRepairResponse toResponse(ApplyDataIntegrityRepairResult result) {
        return new ApplyDataIntegrityRepairResponse(
            result.decision().name(),
            result.recoveryId(),
            result.eventName()
        );
    }
}
