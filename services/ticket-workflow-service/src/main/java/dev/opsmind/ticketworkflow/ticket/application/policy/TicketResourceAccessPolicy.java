package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Translates a resolved {@link TicketViewType} into the correctly scoped
 * {@link TicketQueryPort} call (SPEC-TW-002 §9): requester ownership for
 * Employees, allowed application codes for Support. An empty Support scope
 * never reaches the query port, since an {@code IN ()} predicate is both
 * unnecessary and error-prone — it is treated as an immediate resource miss.
 */
@Component
public class TicketResourceAccessPolicy {

    public Optional<GetTicketResult> loadAuthorizedView(TicketQueryPort queryPort, TicketViewType viewType, GetTicketQuery query) {
        return switch (viewType) {
            case EMPLOYEE_VIEW -> queryPort.findEmployeeView(query.ticketId(), query.actor().subject())
                .map(GetTicketResult.Employee::new);
            case SUPPORT_VIEW -> {
                if (query.allowedApplicationCodes().isEmpty()) {
                    yield Optional.empty();
                }
                yield queryPort.findSupportView(query.ticketId(), query.allowedApplicationCodes())
                    .map(GetTicketResult.Support::new);
            }
            case AUDITOR_VIEW -> Optional.empty();
        };
    }
}
