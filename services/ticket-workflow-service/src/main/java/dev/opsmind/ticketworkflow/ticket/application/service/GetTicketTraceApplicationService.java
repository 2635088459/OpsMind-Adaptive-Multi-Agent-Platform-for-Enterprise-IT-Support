package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketTimelineViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTraceUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTraceQueryPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * SPEC-SC-014: reads the Ticket's latest trace id for the support console's
 * Tempo deep link. Only an IT support actor holding {@code
 * tickets:timeline:internal} may see it — a trace id reveals the shape of
 * internal processing, so it is gated by the same scope as the
 * internal-notes timeline view ({@link TicketTimelineViewPolicy#INTERNAL_SCOPE}).
 *
 * <p>An unknown Ticket returns an empty result (no trace-bearing audit rows
 * exist for it) rather than a 404 — this is a best-effort convenience read,
 * not a resource whose existence this endpoint asserts.
 */
@Service
public class GetTicketTraceApplicationService implements GetTicketTraceUseCase {

    private static final Set<String> SUPPORT_ACTOR_TYPES = Set.of("IT_SUPPORT", "IT_ADMIN", "IT_MANAGER");

    private final TicketTraceQueryPort traceQueryPort;

    public GetTicketTraceApplicationService(TicketTraceQueryPort traceQueryPort) {
        this.traceQueryPort = traceQueryPort;
    }

    @Override
    public Optional<String> latestTraceId(TicketId ticketId, ActorContext actor) {
        if (!SUPPORT_ACTOR_TYPES.contains(actor.actorType()) || !actor.hasScope(TicketTimelineViewPolicy.INTERNAL_SCOPE)) {
            throw new TicketAuthorizationException(TicketTimelineViewPolicy.INTERNAL_SCOPE);
        }
        return traceQueryPort.latestTraceIdForTicket(ticketId);
    }
}
