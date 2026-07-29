package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketResult;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * The API layer is the only place a raw request {@code priority} string is
 * ever parsed — AC-05 requires an unrecognized value (including the
 * internal-only {@code UNASSIGNED} sentinel) to fail with {@code 400
 * VALIDATION_ERROR}, not reach the domain layer at all.
 */
@Component
public class TriageTicketApiMapper {

    public TriageTicketCommand toCommand(
        TicketId ticketId,
        TriageTicketRequest request,
        ActorContext actor,
        Set<String> allowedTeamIds,
        long expectedVersion,
        String idempotencyKey,
        String correlationId,
        String commandId,
        Instant requestedAt
    ) {
        return new TriageTicketCommand(
            ticketId,
            TicketCategoryId.of(request.categoryId()),
            request.subcategoryId() == null ? null : TicketSubcategoryId.of(request.subcategoryId()),
            toPriority(request.priority()),
            SupportQueueId.of(request.supportQueueId()),
            request.reason().trim(),
            expectedVersion,
            actor,
            allowedTeamIds,
            idempotencyKey,
            correlationId,
            commandId,
            requestedAt
        );
    }

    public TriageTicketResponse toResponse(TriageTicketResult result) {
        return new TriageTicketResponse(
            result.ticketId().value(),
            result.status().name(),
            result.categoryId().value(),
            result.subcategoryId() == null ? null : result.subcategoryId().value(),
            result.priority().name(),
            result.supportQueueId().value(),
            result.triagedBy(),
            result.triagedAt(),
            result.version()
        );
    }

    private TicketPriority toPriority(String raw) {
        TicketPriority priority;
        try {
            priority = TicketPriority.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException("priority must be one of LOW, MEDIUM, HIGH, CRITICAL");
        }
        if (priority == TicketPriority.UNASSIGNED) {
            throw new RequestValidationException("priority must be one of LOW, MEDIUM, HIGH, CRITICAL");
        }
        return priority;
    }
}
