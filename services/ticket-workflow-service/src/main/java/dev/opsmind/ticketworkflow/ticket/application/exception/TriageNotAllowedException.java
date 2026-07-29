package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a Requester (or any actor type never permitted to triage)
 * attempts Triage (SPEC-TW-007 AC-07, domain-rules §6: "Requesters cannot
 * triage"). Distinct from {@link QueueAccessDeniedException}, which covers
 * an eligible actor type that simply lacks the required scope or queue
 * grant.
 */
public class TriageNotAllowedException extends RuntimeException {

    public TriageNotAllowedException() {
        super("the actor is not permitted to triage tickets");
    }
}
