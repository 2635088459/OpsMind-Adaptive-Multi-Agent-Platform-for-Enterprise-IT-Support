package dev.opsmind.ticketworkflow.ticket.api.support;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SPEC-SC-014: {@code GET /api/v1/tickets/{ticketId}/trace} — the latest
 * OpenTelemetry trace id for a Ticket, or {@code null} when none has been
 * recorded yet. {@code @JsonInclude(ALWAYS)} so {@code traceId} is always
 * present in the body (as {@code null}), never dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TicketTraceResponse(String traceId) {
}
