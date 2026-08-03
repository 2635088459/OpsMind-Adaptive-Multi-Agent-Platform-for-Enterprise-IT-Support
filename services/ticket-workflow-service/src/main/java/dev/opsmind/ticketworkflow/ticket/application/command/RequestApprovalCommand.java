package dev.opsmind.ticketworkflow.ticket.application.command;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApprovalRiskLevel;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record RequestApprovalCommand(
    TicketId ticketId,
    String workflowId,
    String actionId,
    String actionType,
    ApprovalRiskLevel riskLevel,
    Map<String, Object> riskContext,
    String reason,
    long expectedVersion,
    ActorContext actor,
    Set<String> allowedTeamIds,
    String idempotencyKey,
    String correlationId,
    String commandId,
    Instant requestedAt
) {

    public RequestApprovalCommand {
        riskContext = riskContext == null ? Map.of() : Map.copyOf(riskContext);
    }
}
