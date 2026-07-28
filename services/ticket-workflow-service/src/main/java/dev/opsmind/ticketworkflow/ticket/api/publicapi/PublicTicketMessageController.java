package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.AddTicketMessageResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single physical endpoint for Add Ticket Message, {@code POST
 * /api/v1/tickets/{ticketId}/messages} (SPEC-TW-004 §3). As with Get
 * Ticket (SPEC-TW-002 §6), the server resolves Employee vs. Support
 * semantics from the trusted JWT rather than routing to two controllers on
 * the same path.
 */
@RestController
public class PublicTicketMessageController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final AddTicketMessageUseCase addTicketMessageUseCase;
    private final PublicTicketMessageApiMapper mapper;

    public PublicTicketMessageController(AddTicketMessageUseCase addTicketMessageUseCase, PublicTicketMessageApiMapper mapper) {
        this.addTicketMessageUseCase = addTicketMessageUseCase;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/tickets/{ticketId}/messages")
    public ResponseEntity<AddTicketMessageResponse> addMessage(
        @PathVariable UUID ticketId,
        @Valid @RequestBody AddTicketMessageRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        validateIdempotencyKey(idempotencyKey);

        String resolvedCorrelationId = (correlationId == null || correlationId.isBlank())
            ? UUID.randomUUID().toString()
            : correlationId;

        ActorContext actor = new ActorContext(
            resolveActorType(jwt),
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        AddTicketMessageCommand command = mapper.toCommand(
            TicketId.of(ticketId),
            request,
            actor,
            extractAllowedApplicationCodes(jwt),
            idempotencyKey,
            resolvedCorrelationId,
            UUID.randomUUID().toString(),
            Instant.now()
        );

        AddTicketMessageResult result = addTicketMessageUseCase.addMessage(command);
        AddTicketMessageResponse body = mapper.toResponse(result);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/tickets/" + ticketId + "/messages/" + result.messageId().value()))
            .eTag(String.valueOf(result.version()));

        if (result.idempotencyReplayed()) {
            response.header("Idempotency-Replayed", "true");
        }

        return response.body(body);
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
