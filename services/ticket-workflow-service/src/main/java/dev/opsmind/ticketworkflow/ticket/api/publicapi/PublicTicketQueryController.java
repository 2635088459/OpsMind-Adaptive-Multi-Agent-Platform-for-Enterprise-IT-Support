package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ListRequesterTicketsUseCase;
import dev.opsmind.ticketworkflow.ticket.application.query.ConditionalGetResult;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.GetTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.query.ListRequesterTicketsQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single physical endpoint for Get Ticket, {@code GET
 * /api/v1/tickets/{ticketId}} (SPEC-TW-002 §6). The server resolves
 * Employee vs. Support view from the trusted JWT, never from a request
 * parameter or header (§5); a second controller mapped to the same path
 * would produce an ambiguous Spring MVC mapping, so both actor-specific
 * response types are rendered from this one route.
 */
@RestController
public class PublicTicketQueryController {

    private final GetTicketUseCase getTicketUseCase;
    private final PublicTicketQueryApiMapper employeeMapper;
    private final SupportTicketQueryApiMapper supportMapper;
    private final ListRequesterTicketsUseCase listRequesterTicketsUseCase;
    private final RequesterTicketListApiMapper listMapper;

    public PublicTicketQueryController(
        GetTicketUseCase getTicketUseCase,
        PublicTicketQueryApiMapper employeeMapper,
        SupportTicketQueryApiMapper supportMapper,
        ListRequesterTicketsUseCase listRequesterTicketsUseCase,
        RequesterTicketListApiMapper listMapper
    ) {
        this.getTicketUseCase = getTicketUseCase;
        this.employeeMapper = employeeMapper;
        this.supportMapper = supportMapper;
        this.listRequesterTicketsUseCase = listRequesterTicketsUseCase;
        this.listMapper = listMapper;
    }

    @GetMapping("/api/v1/tickets")
    public ResponseEntity<RequesterTicketListResponse> listTickets(
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) List<String> applicationCode,
        @RequestParam(required = false) Instant createdFrom,
        @RequestParam(required = false) Instant createdTo,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String cursor,
        @AuthenticationPrincipal Jwt jwt
    ) {
        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        TicketListFilters filters = listMapper.toFilters(status, applicationCode, createdFrom, createdTo);
        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(actor, filters, limit, cursor);

        RequesterTicketListResult result = listRequesterTicketsUseCase.list(query);
        RequesterTicketListResponse body = listMapper.toResponse(result);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        headers.set(HttpHeaders.VARY, "Authorization");

        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @GetMapping("/api/v1/tickets/{ticketId}")
    public ResponseEntity<Object> getTicket(
        @PathVariable UUID ticketId,
        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
        @AuthenticationPrincipal Jwt jwt
    ) {
        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        GetTicketQuery query = new GetTicketQuery(
            TicketId.of(ticketId),
            actor,
            extractAllowedApplicationCodes(jwt),
            ifNoneMatch
        );

        ConditionalGetResult conditionalResult = getTicketUseCase.get(query);

        if (conditionalResult instanceof ConditionalGetResult.NotModified notModified) {
            HttpHeaders headers = securityHeaders(notModified.version());
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }

        ConditionalGetResult.Found found = (ConditionalGetResult.Found) conditionalResult;
        GetTicketResult result = found.result();
        HttpHeaders headers = securityHeaders(result.version());

        Object body = switch (result) {
            case GetTicketResult.Employee employee -> employeeMapper.toResponse(employee.projection());
            case GetTicketResult.Support support -> supportMapper.toResponse(support.projection());
        };

        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private HttpHeaders securityHeaders(long version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"" + version + "\"");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        headers.setPragma("no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.VARY, "Authorization");
        return headers;
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
