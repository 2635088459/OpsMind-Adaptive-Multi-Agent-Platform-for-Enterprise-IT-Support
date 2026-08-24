package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.EvaluateAuthorizationCommand;
import com.opsmind.identity.application.dto.AuthorizationDecisionView;
import com.opsmind.identity.application.dto.EvaluateAuthorizationRequest;
import com.opsmind.identity.application.port.in.EvaluateAuthorizationUseCase;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 05-api-contracts {@code POST /authorization-decisions} — trusted workload caller. */
@RestController
public class AuthorizationDecisionController {

    private final EvaluateAuthorizationUseCase evaluateAuthorizationUseCase;

    public AuthorizationDecisionController(EvaluateAuthorizationUseCase evaluateAuthorizationUseCase) {
        this.evaluateAuthorizationUseCase = evaluateAuthorizationUseCase;
    }

    @PostMapping("/internal/identity/v1/authorization-decisions")
    public ResponseEntity<AuthorizationDecisionView> evaluate(
        @Valid @RequestBody EvaluateAuthorizationRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        EvaluateAuthorizationCommand command = new EvaluateAuthorizationCommand(
            request.tenantId(), IdentityRequestContext.actorId(authentication), request.subjectId(), request.sessionId(),
            request.action(), request.resourceType(), request.resourceId(), request.requiredRole(), request.requiredScope(),
            IdentityRequestContext.correlationId(httpRequest)
        );
        AuthorizationDecision decision = evaluateAuthorizationUseCase.evaluate(command);
        return ResponseEntity.ok(AuthorizationDecisionView.from(decision));
    }

    @GetMapping("/internal/identity/v1/authorization-decisions/{decisionId}")
    public ResponseEntity<AuthorizationDecisionView> findById(@PathVariable String decisionId) {
        return ResponseEntity.ok(AuthorizationDecisionView.from(evaluateAuthorizationUseCase.findById(decisionId)));
    }
}
