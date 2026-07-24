package dev.opsmind.ticketworkflow.ticket.api.publicapi;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CreateTicketUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tickets")
public class PublicTicketController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final CreateTicketUseCase createTicketUseCase;
    private final PublicTicketApiMapper mapper;

    public PublicTicketController(CreateTicketUseCase createTicketUseCase, PublicTicketApiMapper mapper) {
        this.createTicketUseCase = createTicketUseCase;
        this.mapper = mapper;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_tickets:create')")
    public ResponseEntity<CreateTicketResponse> createTicket(
        @Valid @RequestBody CreateTicketRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        validateIdempotencyKey(idempotencyKey);

        String resolvedCorrelationId = (correlationId == null || correlationId.isBlank())
            ? UUID.randomUUID().toString()
            : correlationId;

        ActorContext actor = new ActorContext(
            "EMPLOYEE",
            jwt.getSubject(),
            resolveClientId(jwt),
            extractScopes(jwt)
        );

        CreateTicketCommand command = mapper.toCommand(
            request,
            actor,
            idempotencyKey,
            resolvedCorrelationId,
            UUID.randomUUID().toString(),
            Instant.now()
        );

        CreateTicketResult result = createTicketUseCase.create(command);
        CreateTicketResponse body = mapper.toResponse(result);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/tickets/" + result.ticketId().value()))
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
