package dev.opsmind.ticketworkflow.ticket.application.query;

/**
 * Support Queue SLA urgency state (SPEC-TW-005 §11). {@code BREACHED} and
 * {@code AT_RISK} are derived at query time from {@code
 * ticket.ticket_sla_cycles.status} plus the fixed {@code evaluationTime} —
 * neither is a stored column — while {@code ACTIVE}/{@code PAUSED} pass
 * through the persisted cycle status and {@code COMPLETED} covers both
 * {@code MET} and {@code CANCELLED} cycles (CANCELLED is not part of this
 * wire-level state set, and cancelled cycles belong to terminal Tickets
 * excluded from the default queue anyway).
 */
public enum SlaQueueState {
    BREACHED(0),
    AT_RISK(1),
    ACTIVE(2),
    PAUSED(3),
    COMPLETED(4);

    private final int urgencyRank;

    SlaQueueState(int urgencyRank) {
        this.urgencyRank = urgencyRank;
    }

    public int urgencyRank() {
        return urgencyRank;
    }
}
