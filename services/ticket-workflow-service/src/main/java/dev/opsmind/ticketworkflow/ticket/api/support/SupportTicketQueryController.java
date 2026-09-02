package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.port.in.QuerySupportQueueUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueScopePort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Support Queue Query, {@code GET /api/v1/support/tickets} (SPEC-TW-005
 * §3). A dedicated controller in {@code ticket.api.support} rather than a
 * third route on {@code PublicTicketQueryController}: the path itself
 * (unlike Get Ticket / List Requester Tickets, which share {@code
 * /api/v1/tickets}) is already Support-specific, so there is no risk of an
 * ambiguous Spring MVC mapping to avoid by merging controllers.
 */
@RestController
public class SupportTicketQueryController {

    private final QuerySupportQueueUseCase querySupportQueueUseCase;
    private final SupportQueueScopePort scopePort;
    private final SupportQueueApiMapper mapper;

    public SupportTicketQueryController(
        QuerySupportQueueUseCase querySupportQueueUseCase,
        SupportQueueScopePort scopePort,
        SupportQueueApiMapper mapper
    ) {
        this.querySupportQueueUseCase = querySupportQueueUseCase;
        this.scopePort = scopePort;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/support/tickets")
    public ResponseEntity<SupportQueueResponse> queryQueue(
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) List<String> priority,
        @RequestParam(required = false) List<String> applicationCode,
        @RequestParam(required = false) List<String> assignedTeam,
        @RequestParam(required = false) String assignedAgent,
        @RequestParam(defaultValue = "false") boolean unassignedOnly,
        @RequestParam(required = false) List<String> slaState,
        @RequestParam(required = false) Instant createdFrom,
        @RequestParam(required = false) Instant createdTo,
        @RequestParam(defaultValue = "25") int limit,
        @RequestParam(required = false) String cursor,
        @AuthenticationPrincipal Jwt jwt
    ) {
        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        SupportQueueScope scope = scopePort.resolve(
            jwt.getClaimAsStringList("support_queues"),
            jwt.getClaimAsStringList("support_teams")
        );

        SupportQueueFilters filters = mapper.toFilters(
            status, priority, applicationCode, assignedTeam, assignedAgent, unassignedOnly, slaState, createdFrom, createdTo
        );

        SupportQueueQuery query = new SupportQueueQuery(actor, scope, filters, limit, cursor);
        SupportQueueResult result = querySupportQueueUseCase.query(query);
        SupportQueueResponse body = mapper.toResponse(result);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        // Real bug found live (SPEC-EP-013/016/017): once real CORS support was added
        // (SecurityConfiguration#corsConfigurationSource), Spring's own CORS filter
        // additionally sets its own Vary: Origin/Access-Control-Request-Method/
        // Access-Control-Request-Headers -- BEFORE this line runs, on every response
        // for a CORS-configured path -- so the final header carries multiple Vary
        // values, this one appended last. This call's own intent (the response also
        // varies by actor identity) is preserved either way; Cache-Control: no-store
        // already forbids any cache from storing the response at all regardless.
        headers.set(HttpHeaders.VARY, "Authorization");

        return new ResponseEntity<>(body, headers, HttpStatus.OK);
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
}
