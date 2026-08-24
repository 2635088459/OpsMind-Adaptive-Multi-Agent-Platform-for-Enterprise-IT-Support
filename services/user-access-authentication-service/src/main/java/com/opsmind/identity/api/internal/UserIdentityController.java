package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.dto.UserIdentityView;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 05-api-contracts {@code GET /users/me}. Auto-links the trusted identity on first sight, mirroring the OIDC-login use case's own identity-resolution step. */
@RestController
public class UserIdentityController {

    private final ProvisionUserUseCase provisionUserUseCase;

    public UserIdentityController(ProvisionUserUseCase provisionUserUseCase) {
        this.provisionUserUseCase = provisionUserUseCase;
    }

    @GetMapping("/internal/identity/v1/users/me")
    public ResponseEntity<UserIdentityView> me(
        @RequestParam String tenantId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        LinkUserIdentityCommand command = new LinkUserIdentityCommand(
            tenantId, verified.issuer(), verified.subject(), null, null, null, IdentityType.HUMAN,
            IdentityRequestContext.correlationId(httpRequest)
        );
        UserIdentity linked = provisionUserUseCase.link(command);
        return ResponseEntity.ok(UserIdentityView.from(linked));
    }
}
