package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * SPEC-TW-037 README §"Goal"/"Core Rules": the recognized recovery-class
 * taxonomy for opening a reconciliation case — the four named classes
 * ("unknown results, cross-service conflicts, stale results, or data
 * inconsistency") plus the generic {@code RECOVERY_REQUIRED} used by the
 * api-contract's own request example when none of the specific classes
 * applies. An unrecognized value fails Jackson deserialization before this
 * enum is ever reached, so the {@code 400 BAD_REQUEST} case (api-contract
 * §"Errors") is handled by Bean Validation, not here.
 */
public enum ReconciliationReasonCode {
    UNKNOWN_RESULT,
    CROSS_SERVICE_CONFLICT,
    STALE_RESULT,
    DATA_INCONSISTENCY,
    RECOVERY_REQUIRED
}
