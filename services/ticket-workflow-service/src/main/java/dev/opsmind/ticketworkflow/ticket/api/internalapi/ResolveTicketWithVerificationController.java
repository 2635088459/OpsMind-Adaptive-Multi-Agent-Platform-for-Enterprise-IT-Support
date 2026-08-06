package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.PreconditionRequiredException;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ResolveTicketWithVerificationResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ResolveTicketWithVerificationUseCase;
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

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SPEC-TW-025 API contract: an internal (trusted-service-to-service), not
 * human-facing, endpoint — mirrors {@code StartVerificationController}'s
 * (SPEC-TW-022) header/version/idempotency handling exactly, under the same
 * {@code /internal/v1/} namespace and without any Support Queue/team
 * resolution, since the caller is a trusted internal orchestrator, not an IT
 * Support agent ({@code ResolveTicketWithVerificationApplicationService#authorize}
 * enforces the {@code SERVICE} actor type instead).
 */
@RestController
public class ResolveTicketWithVerificationController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ResolveTicketWithVerificationUseCase useCase;
    private final ResolveTicketWithVerificationApiMapper mapper;

    public ResolveTicketWithVerificationController(ResolveTicketWithVerificationUseCase useCase, ResolveTicketWithVerificationApiMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @PostMapping("/internal/v1/tickets/{ticketId}/verified-resolution")
    public ResponseEntity<ResolveTicketWithVerificationResponse> resolveWithVerification(
        @PathVariable UUID ticketId,
        @Valid @RequestBody ResolveTicketWithVerificationRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        long expectedVersion = parseIfMatch(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        ResolveTicketWithVerificationCommand command = mapper.toCommand(
            TicketId.of(ticketId), request, actor, expectedVersion,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        ResolveTicketWithVerificationResult result = useCase.resolveWithVerification(command);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .location(URI.create("/internal/v1/tickets/" + ticketId))
            .body(mapper.toResponse(result));
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
}
