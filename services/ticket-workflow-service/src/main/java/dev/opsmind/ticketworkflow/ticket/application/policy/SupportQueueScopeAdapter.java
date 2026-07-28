package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueScopePort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Local policy adapter resolving {@link SupportQueueScope} from raw,
 * already-verified claim values (SPEC-TW-005 §5). Unknown raw values never
 * enter the Domain (mirroring Get Ticket's {@code support_queues}
 * handling, BI-007): they are silently dropped rather than rejected, so a
 * claim carrying a since-retired application or team code degrades to
 * "not authorized for that value" instead of failing the whole request.
 */
@Component
public class SupportQueueScopeAdapter implements SupportQueueScopePort {

    @Override
    public SupportQueueScope resolve(List<String> rawApplicationCodes, List<String> rawTeamIds) {
        Set<ApplicationCode> applicationCodes = new LinkedHashSet<>();
        if (rawApplicationCodes != null) {
            for (String value : rawApplicationCodes) {
                try {
                    applicationCodes.add(ApplicationCode.valueOf(value));
                } catch (IllegalArgumentException ignored) {
                    // Unknown raw values never enter the Domain (BI-007).
                }
            }
        }
        Set<String> teamIds = rawTeamIds == null ? Set.of() : Set.copyOf(rawTeamIds);
        return new SupportQueueScope(applicationCodes, teamIds);
    }
}
