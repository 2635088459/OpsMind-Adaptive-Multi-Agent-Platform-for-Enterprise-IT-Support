package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.EvaluateSensitiveReadAuditResult;
import dev.opsmind.ticketworkflow.ticket.application.port.in.EvaluateSensitiveReadAuditUseCase;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SPEC-TW-034 api-contract: {@code POST
 * /internal/v1/audit/sensitive-read-policy/evaluate}. An internal
 * (trusted-service-to-service), not human-facing, endpoint under the same
 * {@code /internal/v1/} namespace and JWT-principal-resolution shape as
 * {@code SupportQueueAuthorizationController} — the caller's own admission
 * is enforced inside {@code SensitiveReadAuditPolicyApplicationService}, not
 * here, so every entry point into the policy (controller, application
 * service, inline call from Get Ticket/Ticket Timeline) shares one
 * admission rule.
 */
@RestController
public class SensitiveReadAuditPolicyController {

    private final EvaluateSensitiveReadAuditUseCase useCase;
    private final SensitiveReadAuditPolicyEvaluateApiMapper mapper;

    public SensitiveReadAuditPolicyController(EvaluateSensitiveReadAuditUseCase useCase, SensitiveReadAuditPolicyEvaluateApiMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @PostMapping("/internal/v1/audit/sensitive-read-policy/evaluate")
    public ResponseEntity<SensitiveReadAuditPolicyEvaluateResponse> evaluate(
        @Valid @RequestBody SensitiveReadAuditPolicyEvaluateRequest request,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        ActorContext caller = actorFrom(jwt);
        EvaluateSensitiveReadAuditCommand command = mapper.toCommand(
            request, caller, resolveCorrelationId(correlationId), currentTraceId()
        );
        EvaluateSensitiveReadAuditResult result = useCase.evaluate(command);
        return ResponseEntity.ok(mapper.toResponse(result));
    }

    private ActorContext actorFrom(Jwt jwt) {
        return new ActorContext(resolveActorType(jwt), jwt.getSubject(), resolveClientId(jwt), extractScopes(jwt));
    }

    private String resolveCorrelationId(String correlationId) {
        return (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;
    }

    private String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    private String resolveActorType(Jwt jwt) {
        String actorType = jwt.getClaimAsString("actor_type");
        return actorType != null ? actorType : "SERVICE";
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
