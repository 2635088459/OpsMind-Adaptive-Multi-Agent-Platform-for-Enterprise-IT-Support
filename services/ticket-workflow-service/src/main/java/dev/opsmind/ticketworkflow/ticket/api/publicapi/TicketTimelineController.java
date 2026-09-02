package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineResponse;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTimelineUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single physical endpoint for the Ticket Timeline, {@code GET
 * /api/v1/tickets/{ticketId}/timeline} (SPEC-TW-006 §7). The server
 * resolves the view (Employee/Support-public/Support-internal/Auditor)
 * from the trusted JWT alone — a second controller mapped to the same
 * path would produce an ambiguous Spring MVC mapping, so every
 * actor-specific response type is rendered from this one route, mirroring
 * {@code PublicTicketQueryController}'s established precedent (SPEC-TW-002).
 */
@RestController
public class TicketTimelineController {

    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of("limit", "cursor");
    private static final String INTERNAL_VIEW_HEADER = "X-Include-Internal";

    private final GetTicketTimelineUseCase getTicketTimelineUseCase;
    private final EmployeeTimelineApiMapper employeeMapper;
    private final SupportTimelineApiMapper supportMapper;

    public TicketTimelineController(
        GetTicketTimelineUseCase getTicketTimelineUseCase,
        EmployeeTimelineApiMapper employeeMapper,
        SupportTimelineApiMapper supportMapper
    ) {
        this.getTicketTimelineUseCase = getTicketTimelineUseCase;
        this.employeeMapper = employeeMapper;
        this.supportMapper = supportMapper;
    }

    @GetMapping("/api/v1/tickets/{ticketId}/timeline")
    public ResponseEntity<Object> getTimeline(
        @PathVariable UUID ticketId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String cursor,
        @AuthenticationPrincipal Jwt jwt,
        HttpServletRequest request
    ) {
        rejectClientSelectedView(request);

        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        TicketTimelineQuery query = new TicketTimelineQuery(
            TicketId.of(ticketId), actor, extractAllowedApplicationCodes(jwt), limit, cursor
        );

        TicketTimelineResult result = getTicketTimelineUseCase.getTimeline(query);

        Object body = result.viewType() == TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW
            ? employeeMapper.toResponse(result)
            : (Object) toSupportResponse(result);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        headers.setPragma("no-cache");
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

    private SupportTimelineResponse toSupportResponse(TicketTimelineResult result) {
        return supportMapper.toResponse(result);
    }

    /**
     * §7: the client cannot elevate the resolved view through a query
     * parameter or header. {@code limit}/{@code cursor} are the only
     * supported query parameters (§3); anything else — including an
     * {@code includeInternal} parameter or an internal-view header — is a
     * validation error, not a silently-ignored extra field.
     */
    private void rejectClientSelectedView(HttpServletRequest request) {
        List<String> parameterNames = Collections.list(request.getParameterNames());
        boolean hasDisallowedParam = parameterNames.stream().anyMatch(name -> !ALLOWED_QUERY_PARAMS.contains(name));
        if (hasDisallowedParam || request.getHeader(INTERNAL_VIEW_HEADER) != null) {
            throw new RequestValidationException("the Timeline view is resolved by the server and cannot be requested by the client");
        }
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
