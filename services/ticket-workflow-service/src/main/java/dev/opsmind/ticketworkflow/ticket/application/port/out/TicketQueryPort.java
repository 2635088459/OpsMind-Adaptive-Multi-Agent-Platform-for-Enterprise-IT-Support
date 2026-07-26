package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;
import java.util.Set;

/**
 * Query-side port for Get Ticket (SPEC-TW-002 §11). Resource-level
 * authorization is pushed into the implementation's SQL predicate rather
 * than filtered afterward in Java: a Ticket outside the caller's scope must
 * never be loaded in the first place.
 */
public interface TicketQueryPort {

    /**
     * Returns the Employee view only when {@code ticketId} exists and its
     * {@code requesterId} equals {@code requesterId}.
     */
    Optional<EmployeeTicketProjection> findEmployeeView(TicketId ticketId, String requesterId);

    /**
     * Returns the Support view only when {@code ticketId} exists and its
     * {@code applicationCode} is one of {@code allowedApplicationCodes}.
     * Callers must not invoke this with an empty scope.
     */
    Optional<SupportTicketProjection> findSupportView(TicketId ticketId, Set<ApplicationCode> allowedApplicationCodes);
}
