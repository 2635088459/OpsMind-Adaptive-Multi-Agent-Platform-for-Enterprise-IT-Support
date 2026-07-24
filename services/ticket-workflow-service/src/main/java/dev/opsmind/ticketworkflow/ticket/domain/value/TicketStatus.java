package dev.opsmind.ticketworkflow.ticket.domain.value;

public enum TicketStatus {
    NEW,
    TRIAGING,
    INVESTIGATING,
    WAITING_FOR_USER,
    WAITING_FOR_APPROVAL,
    EXECUTING,
    VERIFYING,
    RESOLVED,
    CLOSED,
    ESCALATED,
    FAILED,
    CANCELLED
}
