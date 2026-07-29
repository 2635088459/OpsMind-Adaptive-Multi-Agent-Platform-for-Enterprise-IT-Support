package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineGuard;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineKeysetPosition;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Query-side port for the Ticket Timeline (SPEC-TW-006 §17). {@link
 * #loadGuard} separates "does this Ticket exist and what is its
 * ownership/scope" from {@link #queryTimeline}'s "what items exist",
 * since the latter can legitimately be empty for an authorized Ticket
 * (§22) while the former alone gates the 404 resource-hiding decision.
 */
public interface TicketTimelineQueryPort {

    Optional<TicketTimelineGuard> loadGuard(TicketId ticketId);

    /**
     * @param includeInternal whether {@code INTERNAL_SUPPORT_NOTE} rows are part of the UNION (Support-internal/Auditor views only)
     * @param snapshotAt      the fixed upper bound on {@code occurredAt} for this cursor session (§13)
     * @param cursorPosition  keyset boundary from the previous page, or {@code null} for the first page
     */
    List<TicketTimelineItem> queryTimeline(
        TicketId ticketId,
        boolean includeInternal,
        Instant snapshotAt,
        TicketTimelineKeysetPosition cursorPosition,
        int limitPlusOne
    );
}
