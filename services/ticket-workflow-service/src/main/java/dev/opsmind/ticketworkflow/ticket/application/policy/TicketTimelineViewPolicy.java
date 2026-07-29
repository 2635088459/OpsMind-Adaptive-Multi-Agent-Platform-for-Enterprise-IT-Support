package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import org.springframework.stereotype.Component;

/**
 * Resolves the Ticket Timeline view from the trusted principal only
 * (SPEC-TW-006 §7). The client cannot request a view, an "internal" flag,
 * a role, or a scope through a query parameter, header, or request field.
 * Unlike Get Ticket's {@code TicketViewPolicy} (one view per actor type),
 * a Support actor resolves to one of two views depending on whether they
 * additionally hold the internal-notes scope — this is an item-visibility
 * distinction surfaced as a view, not a separate role.
 */
@Component
public class TicketTimelineViewPolicy {

    public static final String INTERNAL_SCOPE = "tickets:timeline:internal";

    public TicketTimelineViewType resolve(ActorContext actor) {
        return switch (actor.actorType()) {
            case "EMPLOYEE" -> TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW;
            case "IT_SUPPORT", "IT_ADMIN", "IT_MANAGER" ->
                actor.hasScope(INTERNAL_SCOPE) ? TicketTimelineViewType.SUPPORT_INTERNAL_VIEW : TicketTimelineViewType.SUPPORT_PUBLIC_VIEW;
            case "AUDITOR" -> TicketTimelineViewType.AUDITOR_POLICY_VIEW;
            default -> throw new TicketAuthorizationException("tickets:read");
        };
    }

    public String requiredScope(TicketTimelineViewType viewType) {
        return switch (viewType) {
            case EMPLOYEE_PUBLIC_VIEW -> "tickets:read:self";
            case SUPPORT_PUBLIC_VIEW, SUPPORT_INTERNAL_VIEW -> "tickets:read:queue";
            case AUDITOR_POLICY_VIEW -> "tickets:audit:timeline";
        };
    }
}
