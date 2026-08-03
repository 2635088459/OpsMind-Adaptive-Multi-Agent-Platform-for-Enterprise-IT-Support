package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * SPEC-TW-015 / 11-security §22 Event Trust Validation: the envelope's
 * {@code producer} field is not on the allowlist for this event type
 * (EVENT_PRODUCER_NOT_ALLOWED). Callers must classify this as an immediate
 * DLQ plus a security alert; broker authentication alone does not prove
 * business validity.
 */
public class EventProducerNotAllowedException extends NonRetryableConsumedEventException {

    public EventProducerNotAllowedException(String eventType, String producer) {
        super("producer '" + producer + "' is not allowed to publish " + eventType);
    }
}
