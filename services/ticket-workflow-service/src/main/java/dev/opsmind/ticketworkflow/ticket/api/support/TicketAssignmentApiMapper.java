package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class TicketAssignmentApiMapper {

    public AssignTicketCommand toAssignCommand(
        TicketId ticketId, AssignTicketRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new AssignTicketCommand(
            ticketId, request.assigneeId().trim(), request.reason().trim(), expectedVersion, actor, allowedTeamIds,
            idempotencyKey, correlationId, commandId, requestedAt
        );
    }

    public ReassignTicketCommand toReassignCommand(
        TicketId ticketId, AssignTicketRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new ReassignTicketCommand(
            ticketId, request.assigneeId().trim(), request.reason().trim(), expectedVersion, actor, allowedTeamIds,
            idempotencyKey, correlationId, commandId, requestedAt
        );
    }

    public UnassignTicketCommand toUnassignCommand(
        TicketId ticketId, UnassignTicketRequest request, ActorContext actor, Set<String> allowedTeamIds,
        long expectedVersion, String idempotencyKey, String correlationId, String commandId, Instant requestedAt
    ) {
        return new UnassignTicketCommand(
            ticketId, request.reason().trim(), expectedVersion, actor, allowedTeamIds,
            idempotencyKey, correlationId, commandId, requestedAt
        );
    }

    public TicketAssignmentResponse toResponse(TicketAssignmentResult result) {
        TicketAssignmentResponse.Assignee assignee = result.assigneeId() == null
            ? null
            : new TicketAssignmentResponse.Assignee(result.assigneeId(), result.assigneeDisplayName());

        return new TicketAssignmentResponse(
            result.ticketId().value(), result.status().name(), assignee, result.assignedAt(), result.version()
        );
    }
}
