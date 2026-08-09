package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.PreconditionRequiredException;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CancelTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CancelTicketUseCase;
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
 * SPEC-TW-029 API contract: {@code non-terminal mutable state ->
 * CANCELLED} through requester or authorized-support cancellation. Mirrors
 * {@code ConfirmResolutionController}'s (SPEC-TW-026) actor derivation — the
 * primary caller is the ticket's own requester.
 */
@RestController
public class CancelTicketController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final CancelTicketUseCase useCase;
    private final CancelTicketApiMapper mapper;

    public CancelTicketController(CancelTicketUseCase useCase, CancelTicketApiMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/tickets/{ticketId}/cancel")
    public ResponseEntity<CancelTicketResponse> cancel(
        @PathVariable UUID ticketId,
        @Valid @RequestBody CancelTicketRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        long expectedVersion = parseIfMatch(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        CancelTicketCommand command = mapper.toCommand(
            TicketId.of(ticketId), request, actor, expectedVersion,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now(),
            resolveStepUpProof(jwt)
        );

        CancelTicketResult result = useCase.cancel(command);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .location(URI.create("/api/v1/tickets/" + ticketId))
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

    /**
     * SPEC-TW-036: reads already-asserted step-up evidence from the actor's
     * own trusted JWT — the same session that completed step-up with the
     * identity provider is the one calling this endpoint, unlike the
     * internal policy-evaluate endpoint where caller and subject differ.
     * Never a new authentication mechanism (SPEC-TW-036 §2 excludes
     * "replacing baseline Keycloak/OAuth2 authentication"): these are
     * additional claims on the same access token. Returns {@code null} —
     * not a partially-populated proof — when any claim is absent, so the
     * policy always sees "no proof" rather than a malformed one.
     */
    private StepUpProof resolveStepUpProof(Jwt jwt) {
        String proofId = jwt.getClaimAsString("step_up_proof_id");
        String method = jwt.getClaimAsString("step_up_method");
        Instant verifiedAt = instantClaim(jwt, "step_up_verified_at");
        Instant expiresAt = instantClaim(jwt, "step_up_expires_at");
        if (proofId == null && method == null && verifiedAt == null && expiresAt == null) {
            return null;
        }
        return new StepUpProof(proofId, method, verifiedAt, expiresAt);
    }

    /** Tolerant of a numeric epoch-seconds claim (the conventional JWT NumericDate shape) or an ISO-8601 string. */
    private Instant instantClaim(Jwt jwt, String claimName) {
        Object rawValue = jwt.getClaim(claimName);
        if (rawValue instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        if (rawValue instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Instant.parse(stringValue.trim());
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return null;
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
