package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-038 api-contract §"Errors": {@code 404 NOT_FOUND} — the {@code
 * sourceReference} does not match any known original event. This
 * implementation resolves {@code sourceReference} against {@code
 * ticket.outbox_events.event_id} (README §"Goal" names outbox, consumer
 * inbox, and DLQ messages together; this codebase persists only the outbox
 * as a queryable table, so that is what "the target event" means here).
 */
public class ReplaySourceEventNotFoundException extends RuntimeException {

    public ReplaySourceEventNotFoundException() {
        super("the original event referenced by sourceReference was not found");
    }
}
