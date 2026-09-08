package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.sse.TicketEventBroadcaster;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SPEC-EP-014/020's own real backend counterpart: {@code GET
 * /api/v1/tickets/{ticketId}/events}, the route useTicketStatusStream.ts has
 * been calling since before this route existed (its own docstring flagged
 * this as a real, unconfirmed contract -- "no such route exists in
 * ticket-workflow-service's own router package"). This closes that gap.
 *
 * Authorization is the exact same {@link GetTicketUseCase#get} call {@link
 * PublicTicketQueryController#getTicket} already runs -- Employee vs.
 * Support view resolution, scope check, and resource-level ownership all
 * run once at connection-open time, via the identical {@link ActorContext}/
 * {@link GetTicketQuery} shapes, so this endpoint can never authorize a
 * subscriber the plain GET would reject: an actor not allowed to see this
 * ticket at all never gets an {@link SseEmitter}, and gets the exact same
 * 403/404 JSON body {@code GlobalRestExceptionHandler} already renders,
 * since {@code getTicketUseCase.get(...)} throws before any emitter is
 * created. The stream itself never carries ticket content of its own (see
 * {@code TicketOutboxEventAppended}'s own docstring) -- every push just
 * means "go re-fetch" -- so there is no second, narrower-scoped projection
 * for this endpoint to accidentally get wrong.
 *
 * {@code SecurityConfiguration}'s own {@code ticketEventsSecurityFilterChain}
 * is the only reason this route authenticates at all: a browser {@code
 * EventSource} cannot set an {@code Authorization} header, so this is the
 * one route in this service that also accepts a bearer token as a {@code
 * ?token=} query parameter.
 */
@RestController
public class TicketEventStreamController {

    private final GetTicketUseCase getTicketUseCase;
    private final TicketEventBroadcaster broadcaster;

    public TicketEventStreamController(GetTicketUseCase getTicketUseCase, TicketEventBroadcaster broadcaster) {
        this.getTicketUseCase = getTicketUseCase;
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/api/v1/tickets/{ticketId}/events", produces = "text/event-stream")
    public SseEmitter streamTicketEvents(@PathVariable UUID ticketId, @AuthenticationPrincipal Jwt jwt) {
        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        // Authorization-only: discards the real projection (see this
        // class's own javadoc) -- a 403/404 short-circuits before any
        // SseEmitter is ever created, exactly like the plain GET.
        getTicketUseCase.get(new GetTicketQuery(TicketId.of(ticketId), actor, extractAllowedApplicationCodes(jwt), null));

        return broadcaster.subscribe(TicketId.of(ticketId));
    }

    private String resolveActorType(Jwt jwt) {
        String actorType = jwt.getClaimAsString("actor_type");
        return actorType != null ? actorType : "EMPLOYEE";
    }

    private String resolveClientId(Jwt jwt) {
        String authorizedParty = jwt.getClaimAsString("azp");
        return authorizedParty != null ? authorizedParty : jwt.getClaimAsString("client_id");
    }

    private Set<String> extractScopes(Jwt jwt) {
        Object scopeClaim = jwt.getClaim("scope");
        if (scopeClaim instanceof String scopeString && !scopeString.isBlank()) {
            return Set.of(scopeString.trim().split("\\s+"));
        }
        if (scopeClaim instanceof Iterable<?> scopeIterable) {
            return java.util.stream.StreamSupport.stream(scopeIterable.spliterator(), false)
                .map(String::valueOf)
                .collect(Collectors.toSet());
        }
        return Set.of();
    }

    private Set<ApplicationCode> extractAllowedApplicationCodes(Jwt jwt) {
        List<String> raw = jwt.getClaimAsStringList("support_queues");
        if (raw == null) {
            return Set.of();
        }
        Set<ApplicationCode> codes = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                codes.add(ApplicationCode.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // Unknown raw values never enter the Domain (BI-007).
            }
        }
        return codes;
    }
}
