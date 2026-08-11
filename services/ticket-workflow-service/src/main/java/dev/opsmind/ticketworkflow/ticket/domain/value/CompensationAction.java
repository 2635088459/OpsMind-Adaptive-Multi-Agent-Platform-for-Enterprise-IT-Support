package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * SPEC-TW-040 domain-rules: "Compensation must select a defined action and
 * cannot run arbitrary SQL or arbitrary state mutation." A closed,
 * code-defined catalog of compensating actions for a Side Effect Conflict
 * (phase-10 roadmap §4: "external system state disagrees with Ticket") — an
 * unrecognized action fails Jackson deserialization before this enum is
 * ever reached (api-contract §"Errors" {@code 400 BAD_REQUEST}), and the
 * persisted {@code compensation_action} column carries the same closed
 * vocabulary as a DB-level CHECK constraint, so neither the API nor the
 * schema can ever accept an arbitrary action string.
 */
public enum CompensationAction {
    RETRY_SIDE_EFFECT,
    REVERSE_SIDE_EFFECT,
    ACKNOWLEDGE_SIDE_EFFECT,
    MARK_MANUALLY_RECONCILED
}
