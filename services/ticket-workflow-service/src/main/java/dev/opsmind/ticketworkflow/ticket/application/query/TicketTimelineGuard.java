package dev.opsmind.ticketworkflow.ticket.application.query;

/**
 * Minimal projection for Timeline resource-level authorization (SPEC-TW-006
 * §6), loaded once before the (potentially empty) item query runs — mirrors
 * {@code TicketWriteGuard} from Add Ticket Message. Separating "does this
 * Ticket exist and is this actor authorized for it" from "what Timeline
 * items exist" matters because the latter can legitimately be empty for an
 * authorized Ticket (§22, migration/repair case) while the former must
 * still gate the whole response with 404 when it fails.
 */
public record TicketTimelineGuard(
    String displayId,
    String requesterId,
    String applicationCode
) {
}
