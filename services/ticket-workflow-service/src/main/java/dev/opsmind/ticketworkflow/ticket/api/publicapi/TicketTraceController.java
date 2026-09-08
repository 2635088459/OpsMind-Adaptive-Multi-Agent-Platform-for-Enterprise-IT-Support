package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.support.TicketTraceResponse;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketTraceUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SPEC-SC-014 / UC-SC-05: {@code GET /api/v1/tickets/{ticketId}/trace}. A
 * tiny read the support console uses to build a "open the full trace in
 * Tempo" deep link on the ticket detail view, so an agent does not have to
 * paste a trace id by hand on the Observability page. Internal data (a
 * trace id exposes internal processing shape) — {@link
 * GetTicketTraceUseCase} requires {@code tickets:timeline:internal}.
 *
 * <p>Deliberately its own route rather than a field on the governed Get
 * Ticket / Timeline responses (SPEC-TW-002 §13 / SPEC-TW-006 §20, both
 * {@code additionalProperties: false} schemas): the trace id lives in
 * {@code audit_records}, not in any timeline source table, and this keeps
 * the change off those contracts entirely.
 */
@RestController
public class TicketTraceController {

    private final GetTicketTraceUseCase getTicketTraceUseCase;

    public TicketTraceController(GetTicketTraceUseCase getTicketTraceUseCase) {
        this.getTicketTraceUseCase = getTicketTraceUseCase;
    }

    @GetMapping("/api/v1/tickets/{ticketId}/trace")
    public ResponseEntity<TicketTraceResponse> getTrace(@PathVariable UUID ticketId, @AuthenticationPrincipal Jwt jwt) {
        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        String traceId = getTicketTraceUseCase.latestTraceId(TicketId.of(ticketId), actor).orElse(null);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return new ResponseEntity<>(new TicketTraceResponse(traceId), headers, org.springframework.http.HttpStatus.OK);
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
