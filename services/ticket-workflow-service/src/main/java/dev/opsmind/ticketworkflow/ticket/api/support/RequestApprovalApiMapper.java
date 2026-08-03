package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.RequestApprovalResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class RequestApprovalApiMapper {

    public RequestApprovalCommand toCommand(
        TicketId ticketId, RequestApprovalRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new RequestApprovalCommand(
            ticketId,
            request.workflowId().trim(),
            request.actionId().trim(),
            request.actionType().trim(),
            request.riskLevel(),
            request.riskContext(),
            request.reason() == null ? null : request.reason().trim(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public RequestApprovalResponse toResponse(RequestApprovalResult result) {
        return new RequestApprovalResponse(
            result.ticketId().value(),
            result.approvalRequestId(),
            result.approvalId(),
            result.previousStatus().name(),
            result.status().name(),
            result.workflowId(),
            result.actionId(),
            result.actionType(),
            result.riskLevel().name(),
            result.requestedBy(),
            result.requestedAt(),
            result.version()
        );
    }
}
