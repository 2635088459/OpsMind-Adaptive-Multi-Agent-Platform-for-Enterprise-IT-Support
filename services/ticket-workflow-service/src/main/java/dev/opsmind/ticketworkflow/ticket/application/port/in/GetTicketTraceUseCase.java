package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

/**
 * SPEC-SC-014 / UC-SC-05: "View the complete call chain for how a ticket was
 * processed" — resolve the Ticket's latest trace id so the support console
 * can build a Tempo deep link. Internal data (a trace id exposes the shape
 * of internal processing), so it requires the internal-timeline scope, the
 * same one that gates {@code SUPPORT_INTERNAL_VIEW} on the timeline.
 */
public interface GetTicketTraceUseCase {

    /** Empty when the Ticket has no trace-bearing audit record yet. Throws on a missing scope or unknown Ticket. */
    Optional<String> latestTraceId(TicketId ticketId, ActorContext actor);
}
