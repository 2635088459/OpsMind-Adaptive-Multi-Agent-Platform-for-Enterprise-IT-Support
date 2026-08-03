package dev.opsmind.ticketworkflow.ticket.api.support;

import dev.opsmind.ticketworkflow.ticket.api.exception.PreconditionRequiredException;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TransitionTicketStatusResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.TransitionTicketStatusUseCase;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
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

@RestController
public class TransitionTicketStatusController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /**
     * SPEC-TW-012 introduces a dedicated {@code POST .../user-input-requests}
     * command for {@code IN_PROGRESS -> WAITING_FOR_USER} (transitionId
     * {@code SM-014}), which also creates the open user-input-request row
     * this codebase's {@code WAITING_FOR_USER} invariant now requires.
     * {@code WAITING_FOR_USER} is therefore removed from this generic
     * endpoint's allowed targets — reaching it here would leave the ticket
     * waiting with no request to answer. {@code Ticket.transitionStatus()}
     * still supports it at the domain layer (SM-006) for the same reason
     * {@code Ticket.REASSIGNABLE_STATUSES} etc. stay general-purpose; only
     * the HTTP surface narrows.
     */
    private static final Set<TicketStatus> ALLOWED_TARGET_STATUSES = Set.of(
        TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_APPROVAL
    );

    private final TransitionTicketStatusUseCase useCase;
    private final TransitionTicketStatusApiMapper mapper;

    public TransitionTicketStatusController(TransitionTicketStatusUseCase useCase, TransitionTicketStatusApiMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/tickets/{ticketId}/status-transitions")
    public ResponseEntity<TransitionTicketStatusResponse> transition(
        @PathVariable UUID ticketId,
        @Valid @RequestBody TransitionTicketStatusRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        validateTargetMetadata(request);
        long expectedVersion = parseIfMatch(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        ActorContext actor = actorFrom(jwt);

        TransitionTicketStatusCommand command = mapper.toCommand(
            TicketId.of(ticketId), request, actor, extractAllowedTeamIds(jwt), expectedVersion,
            idempotencyKey, resolveCorrelationId(correlationId), UUID.randomUUID().toString(), Instant.now()
        );

        TransitionTicketStatusResult result = useCase.transition(command);
        return ResponseEntity.ok()
            .eTag(String.valueOf(result.version()))
            .body(mapper.toResponse(result));
    }

    private void validateTargetMetadata(TransitionTicketStatusRequest request) {
        if (!ALLOWED_TARGET_STATUSES.contains(request.targetStatus())) {
            throw new RequestValidationException("targetStatus must be IN_PROGRESS or WAITING_FOR_APPROVAL");
        }
        if (request.targetStatus() == TicketStatus.WAITING_FOR_APPROVAL) {
            if (request.approvalReference() == null || request.approvalReference().isBlank()) {
                throw new RequestValidationException("approvalReference is required for WAITING_FOR_APPROVAL");
            }
            if (request.waitingForRequesterSince() != null) {
                throw new RequestValidationException("waitingForRequesterSince is only allowed for WAITING_FOR_USER");
            }
        } else if (request.waitingForRequesterSince() != null || (request.approvalReference() != null && !request.approvalReference().isBlank())) {
            throw new RequestValidationException("waiting metadata is only allowed for waiting statuses");
        }
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
