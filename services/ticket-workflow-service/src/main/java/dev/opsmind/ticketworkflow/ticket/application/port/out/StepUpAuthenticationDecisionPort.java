package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.StepUpAuthenticationDecisionEntry;

public interface StepUpAuthenticationDecisionPort {

    /**
     * Appends a required Step-up Authentication policy-decision record
     * (SPEC-TW-036 persistence). Implementations must not swallow failures:
     * a failed append must propagate so an {@code ALLOW} decision that
     * cannot be durably recorded fails closed rather than being silently
     * granted.
     */
    void record(StepUpAuthenticationDecisionEntry entry);
}
