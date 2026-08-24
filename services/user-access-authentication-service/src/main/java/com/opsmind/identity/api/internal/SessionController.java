package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.dto.StartSessionRequest;
import com.opsmind.identity.application.dto.UserSessionView;
import com.opsmind.identity.application.port.in.ManageSessionUseCase;
import com.opsmind.identity.domain.session.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The direct entry point to session start — SPEC-UA-005 (Authorization Code
 * PKCE Login Callback) adds the real browser OIDC flow as another caller of
 * the same {@code ManageSessionUseCase#start}, not a replacement.
 */
@RestController
public class SessionController {

    private final ManageSessionUseCase manageSessionUseCase;

    public SessionController(ManageSessionUseCase manageSessionUseCase) {
        this.manageSessionUseCase = manageSessionUseCase;
    }

    @PostMapping("/internal/identity/v1/sessions")
    public ResponseEntity<UserSessionView> start(
        @Valid @RequestBody StartSessionRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        StartSessionCommand command = new StartSessionCommand(
            request.tenantId(), verified.issuer(), verified.subject(), request.idpSessionIdHash(), request.tokenIdHash(),
            request.clientId(), request.acr(), request.amr(), java.time.Instant.now(), request.deviceIdHash(),
            Duration.ofSeconds(request.ttlSeconds()), IdentityRequestContext.correlationId(httpRequest)
        );
        UserSession session = manageSessionUseCase.start(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserSessionView.from(session));
    }

    @GetMapping("/internal/identity/v1/sessions/{sessionId}")
    public ResponseEntity<UserSessionView> findById(@PathVariable String sessionId) {
        return ResponseEntity.ok(UserSessionView.from(manageSessionUseCase.findById(sessionId)));
    }

    /** 05-api-contracts {@code POST /sessions/{id}/revoke} — self or admin. */
    @PostMapping("/internal/identity/v1/sessions/{sessionId}/revoke")
    public ResponseEntity<UserSessionView> revoke(
        @PathVariable String sessionId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        RevokeSessionCommand command = new RevokeSessionCommand(
            sessionId, IdentityRequestContext.actorId(authentication), "revoked by caller", IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(UserSessionView.from(manageSessionUseCase.revoke(command)));
    }
}
