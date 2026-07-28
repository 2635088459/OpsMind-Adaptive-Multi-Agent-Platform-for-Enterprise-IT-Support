package dev.opsmind.ticketworkflow.ticket.application.query;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;

/**
 * Wire-level priority label for the Support Queue (SPEC-TW-005 §12,
 * §17). The persisted {@link TicketPriority} scale (UNASSIGNED/LOW/MEDIUM/
 * HIGH/CRITICAL, established by SPEC-TW-001) does not match this spec's
 * required {@code UNASSIGNED/P1/P2/P3/P4} response and filter contract, so
 * this is the single mapping point between the two: CRITICAL is the most
 * urgent (P1, rank 0) and UNASSIGNED is least urgent (rank 4), preserving
 * the existing severity ordering under the new labels.
 */
public enum SupportQueuePriority {
    P1(TicketPriority.CRITICAL, 0),
    P2(TicketPriority.HIGH, 1),
    P3(TicketPriority.MEDIUM, 2),
    P4(TicketPriority.LOW, 3),
    UNASSIGNED(TicketPriority.UNASSIGNED, 4);

    private final TicketPriority ticketPriority;
    private final int priorityRank;

    SupportQueuePriority(TicketPriority ticketPriority, int priorityRank) {
        this.ticketPriority = ticketPriority;
        this.priorityRank = priorityRank;
    }

    public TicketPriority ticketPriority() {
        return ticketPriority;
    }

    public int priorityRank() {
        return priorityRank;
    }

    public static SupportQueuePriority fromTicketPriority(TicketPriority priority) {
        for (SupportQueuePriority value : values()) {
            if (value.ticketPriority == priority) {
                return value;
            }
        }
        throw new IllegalArgumentException("unmapped TicketPriority: " + priority);
    }
}
