package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.ApplyDataIntegrityRepairResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.ApplyDataIntegrityRepairUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * SPEC-TW-041 API contract: an internal (trusted-service-to-service), not
 * human-facing, endpoint — mirrors {@code ReplayEventController}'s
 * (SPEC-TW-038) header/idempotency handling. Like that endpoint, this one
 * has no {@code {ticketId}} path variable — {@code
 * /internal/v1/tickets/integrity-repairs} is not ticket-scoped, since the
 * recovery target is a scan finding, not a ticket directly.
 */
@RestController
public class ApplyDataIntegrityRepairController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ApplyDataIntegrityRepairUseCase useCase;
    private final ApplyDataIntegrityRepairApiMapper mapper;

    public ApplyDataIntegrityRepairController(ApplyDataIntegrityRepairUseCase useCase, ApplyDataIntegrityRepairApiMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @PostMapping("/internal/v1/tickets/integrity-repairs")
    public ResponseEntity<ApplyDataIntegrityRepairResponse> apply(
        @Valid @RequestBody ApplyDataIntegrityRepairRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        ApplyDataIntegrityRepairCommand command = mapper.toCommand(
            request, actor, idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        ApplyDataIntegrityRepairResult result = useCase.apply(command);
        return ResponseEntity.ok()
            .location(URI.create("/internal/v1/tickets/integrity-repairs/" + result.recoveryId()))
            .body(mapper.toResponse(result));
    }

    private ActorContext actorFrom(Jwt jwt) {
        return new ActorContext(resolveActorType(jwt), jwt.getSubject(), resolveClientId(jwt), extractScopes(jwt));
    }

    private String resolveCorrelationId(String correlationId) {
        return (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;
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
