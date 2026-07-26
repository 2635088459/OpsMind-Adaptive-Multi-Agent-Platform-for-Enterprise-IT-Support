package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import org.springframework.stereotype.Component;

/**
 * Resolves the Get Ticket view from the trusted principal only (SPEC-TW-002
 * §5). The client cannot request a higher-privilege view through a query
 * parameter, header, or request field: {@code ActorContext.actorType} is
 * derived from the JWT, never from client-supplied request data.
 */
@Component
public class TicketViewPolicy {

    public TicketViewType resolve(ActorContext actor) {
        return switch (actor.actorType()) {
            case "EMPLOYEE" -> TicketViewType.EMPLOYEE_VIEW;
            case "IT_SUPPORT", "IT_ADMIN", "IT_MANAGER" -> TicketViewType.SUPPORT_VIEW;
            case "AUDITOR" -> TicketViewType.AUDITOR_VIEW;
            default -> throw new TicketAuthorizationException("tickets:read");
        };
    }

    public String requiredScope(TicketViewType viewType) {
        return switch (viewType) {
            case EMPLOYEE_VIEW -> "tickets:read:self";
            case SUPPORT_VIEW -> "tickets:read:queue";
            case AUDITOR_VIEW -> "tickets:audit:read";
        };
    }
}
