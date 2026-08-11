package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.PublishCorrectionEventResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.PublishCorrectionEventUseCase;
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
 * SPEC-TW-039 API contract: an internal (trusted-service-to-service), not
 * human-facing, endpoint — mirrors {@code OpenReconciliationCaseController}'s
 * (SPEC-TW-037) header/idempotency handling exactly, minus the {@code
 * If-Match} ticket-version precondition: domain-rules "Correction events
 * must not delete or rewrite original events" means this SPEC never mutates
 * the Ticket aggregate, so there is no ticket version to condition the
 * request on.
 */
@RestController
public class PublishCorrectionEventController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final PublishCorrectionEventUseCase useCase;
    private final PublishCorrectionEventApiMapper mapper;

    public PublishCorrectionEventController(PublishCorrectionEventUseCase useCase, PublishCorrectionEventApiMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @PostMapping("/internal/v1/tickets/{ticketId}/correction-events")
    public ResponseEntity<PublishCorrectionEventResponse> publish(
        @PathVariable UUID ticketId,
        @Valid @RequestBody PublishCorrectionEventRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        PublishCorrectionEventCommand command = mapper.toCommand(
            TicketId.of(ticketId), request, actor,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        PublishCorrectionEventResult result = useCase.publish(command);
        return ResponseEntity.ok()
            .location(URI.create("/internal/v1/tickets/" + ticketId + "/correction-events/" + result.recoveryId()))
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
