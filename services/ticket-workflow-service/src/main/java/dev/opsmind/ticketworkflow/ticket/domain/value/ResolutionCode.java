package dev.opsmind.ticketworkflow.ticket.domain.value;

/** SPEC-TW-010 §5: the controlled resolution-code vocabulary. */
public enum ResolutionCode {
    FIXED,
    WORKAROUND_PROVIDED,
    DUPLICATE,
    REQUEST_FULFILLED,
    NOT_REPRODUCIBLE,
    USER_ERROR,
    NO_ACTION_REQUIRED
}
