package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.api.exception.PreconditionRequiredException;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AssignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ReassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TicketAssignmentResult;
import dev.opsmind.ticketworkflow.ticket.application.command.UnassignTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AssignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ReassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.application.port.in.UnassignTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code POST /api/v1/tickets/{ticketId}/{assign,reassign,unassign}}
 * (SPEC-TW-008 §6). Support and Automation Agent identities only, same
 * reasoning as {@code TriageTicketController} (SPEC-TW-007) for living in
 * {@code ticket.api.support} rather than a shared public/support route.
 * Reuses the same {@code If-Match}/{@code Idempotency-Key} mechanics
 * (412/428) as Triage rather than SPEC-TW-008's own literal text (which
 * groups version conflict under 409 and omits 428) — see the traceability
 * entry's known deviations for the reasoning: one consistent optimistic-
 * locking contract per service, not one per spec author's word choice.
 */
@RestController
public class TicketAssignmentController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final AssignTicketUseCase assignTicketUseCase;
    private final ReassignTicketUseCase reassignTicketUseCase;
    private final UnassignTicketUseCase unassignTicketUseCase;
    private final TicketAssignmentApiMapper mapper;

    public TicketAssignmentController(
        AssignTicketUseCase assignTicketUseCase,
        ReassignTicketUseCase reassignTicketUseCase,
        UnassignTicketUseCase unassignTicketUseCase,
        TicketAssignmentApiMapper mapper
    ) {
        this.assignTicketUseCase = assignTicketUseCase;
        this.reassignTicketUseCase = reassignTicketUseCase;
        this.unassignTicketUseCase = unassignTicketUseCase;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/tickets/{ticketId}/assign")
    public ResponseEntity<TicketAssignmentResponse> assign(
        @PathVariable UUID ticketId,
        @Valid @RequestBody AssignTicketRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        long expectedVersion = parseIfMatch(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        AssignTicketCommand command = mapper.toAssignCommand(
            TicketId.of(ticketId), request, actor, extractAllowedTeamIds(jwt), expectedVersion,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        TicketAssignmentResult result = assignTicketUseCase.assign(command);
        return respond(result);
    }

    @PostMapping("/api/v1/tickets/{ticketId}/reassign")
    public ResponseEntity<TicketAssignmentResponse> reassign(
        @PathVariable UUID ticketId,
        @Valid @RequestBody AssignTicketRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        long expectedVersion = parseIfMatch(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        ReassignTicketCommand command = mapper.toReassignCommand(
            TicketId.of(ticketId), request, actor, extractAllowedTeamIds(jwt), expectedVersion,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        TicketAssignmentResult result = reassignTicketUseCase.reassign(command);
        return respond(result);
    }

    @PostMapping("/api/v1/tickets/{ticketId}/unassign")
    public ResponseEntity<TicketAssignmentResponse> unassign(
        @PathVariable UUID ticketId,
        @Valid @RequestBody UnassignTicketRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        long expectedVersion = parseIfMatch(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        UnassignTicketCommand command = mapper.toUnassignCommand(
            TicketId.of(ticketId), request, actor, extractAllowedTeamIds(jwt), expectedVersion,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        TicketAssignmentResult result = unassignTicketUseCase.unassign(command);
        return respond(result);
    }

    private ResponseEntity<TicketAssignmentResponse> respond(TicketAssignmentResult result) {
        TicketAssignmentResponse body = mapper.toResponse(result);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .body(body);
    }

    private ActorContext actorFrom(Jwt jwt) {
        return new ActorContext(resolveActorType(jwt), jwt.getSubject(), resolveClientId(jwt), extractScopes(jwt));
    }

    private String resolveCorrelationId(String correlationId) {
        return (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;
    }

    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String unquoted = ifMatch.trim();
        if (unquoted.startsWith("\"") && unquoted.endsWith("\"") && unquoted.length() >= 2) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        try {
            return Long.parseLong(unquoted);
        } catch (NumberFormatException e) {
            throw new RequestValidationException("If-Match must be a valid ticket version ETag");
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new RequestValidationException("Idempotency-Key header is required and must be 1-128 characters");
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

    private Set<String> extractAllowedTeamIds(Jwt jwt) {
        List<String> raw = jwt.getClaimAsStringList("support_teams");
        return raw == null ? Set.of() : new LinkedHashSet<>(raw);
    }
}
