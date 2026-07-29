package dev.opsmind.ticketworkflow.ticket.application.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketTimelineResult(
    UUID ticketId,
    String displayId,
    TicketTimelineViewType viewType,
    List<TicketTimelineItem> items,
    int limit,
    boolean hasMore,
    String nextCursor,
    Instant snapshotAt
) {
}
