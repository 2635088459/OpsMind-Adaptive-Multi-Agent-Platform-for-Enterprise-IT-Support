package dev.opsmind.ticketworkflow.ticket.application.port.out;

import dev.opsmind.ticketworkflow.ticket.application.model.SupportQueueAuthorizationDecisionEntry;

public interface SupportQueueAuthorizationDecisionPort {

    /**
     * Appends a required Support Queue authorization decision record
     * (SPEC-TW-033 persistence). Implementations must not swallow
     * failures: a failed append must propagate so an {@code ALLOW} decision
     * that cannot be durably recorded fails closed rather than being
     * silently granted (domain-rules: "Fail-closed behavior cannot be
     * bypassed by fallback, retry, or partial response").
     */
    void record(SupportQueueAuthorizationDecisionEntry entry);
}
