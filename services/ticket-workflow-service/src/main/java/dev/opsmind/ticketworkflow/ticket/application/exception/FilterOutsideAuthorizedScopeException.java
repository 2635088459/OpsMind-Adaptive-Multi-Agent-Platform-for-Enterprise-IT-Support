package dev.opsmind.ticketworkflow.ticket.application.exception;

/**
 * Raised when a requested Support Queue filter value (applicationCode,
 * assignedTeam, assignedAgent) falls outside the actor's authorized scope
 * (SPEC-TW-005 §7). Distinct from {@link TicketAuthorizationException}
 * (missing the Queue scope entirely, 403 FORBIDDEN): this is 403
 * FILTER_OUTSIDE_AUTHORIZED_SCOPE, and never reveals the actor's full
 * authorized scope or which specific value was rejected.
 */
public class FilterOutsideAuthorizedScopeException extends RuntimeException {

    public FilterOutsideAuthorizedScopeException() {
        super("requested filter is outside the authorized Support Queue scope");
    }
}
