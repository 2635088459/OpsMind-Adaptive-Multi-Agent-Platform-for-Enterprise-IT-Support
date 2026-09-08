package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.util.Optional;

/**
 * The most recent OpenTelemetry trace id recorded against a Ticket
 * (SPEC-SC-014 / UC-SC-05: the support console links out to Tempo from a
 * ticket's own detail view, keyed on the trace of its latest processing).
 *
 * <p>Sourced from {@code ticket.audit_records.trace_id} — every business
 * action on a Ticket writes one audit row carrying the request's real
 * W3C trace id. Timeline items themselves (status history / messages)
 * carry no trace id, so this is a separate, tiny read rather than a
 * per-item field on the governed timeline contract.
 */
public interface TicketTraceQueryPort {

    /**
     * The {@code trace_id} of the most recent audit record for this Ticket,
     * or empty when none has a trace id (or the Ticket has no audit rows).
     * Does no authorization of its own — the caller has already resolved the
     * Support view + scope.
     */
    Optional<String> latestTraceIdForTicket(TicketId ticketId);
}
