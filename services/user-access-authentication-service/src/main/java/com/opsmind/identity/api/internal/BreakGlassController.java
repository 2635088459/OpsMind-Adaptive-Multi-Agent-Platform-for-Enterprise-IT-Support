package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.ActivateBreakGlassCommand;
import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.command.RevokeBreakGlassCommand;
import com.opsmind.identity.application.dto.ActivateBreakGlassRequest;
import com.opsmind.identity.application.dto.BreakGlassGrantView;
import com.opsmind.identity.application.dto.RevokeBreakGlassRequest;
import com.opsmind.identity.application.port.in.EnforceIdentityPermissionUseCase;
import com.opsmind.identity.application.port.in.ManageBreakGlassUseCase;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
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
 * SPEC-UA-019 (Break Glass And Account Recovery). Not named in
 * 05-api-contracts (its own footer never claims this spec) — these paths
 * follow the same {@code /internal/identity/v1/*} convention every other
 * resource in this API already uses. {@code identity:user:admin} gates
 * both operations (SPEC-UA-011's own catalog is deliberately never widened
 * with an invented break-glass-specific permission string no LLD section
 * names — this reuses the closest already-grounded admin authority
 * instead). The activating admin is always the grant's own recipient
 * (issuer/subject from the verified JWT, never a request-body field).
 */
@RestController
public class BreakGlassController {

    private final ManageBreakGlassUseCase manageBreakGlassUseCase;
    private final EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public BreakGlassController(
        ManageBreakGlassUseCase manageBreakGlassUseCase, EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase,
        BrowserLoginProperties browserLoginProperties
    ) {
        this.manageBreakGlassUseCase = manageBreakGlassUseCase;
        this.enforceIdentityPermissionUseCase = enforceIdentityPermissionUseCase;
        this.browserLoginProperties = browserLoginProperties;
    }

    @PostMapping("/internal/identity/v1/break-glass/activate")
    public ResponseEntity<BreakGlassGrantView> activate(
        @Valid @RequestBody ActivateBreakGlassRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        String correlationId = IdentityRequestContext.correlationId(httpRequest);
        IdentityRequestContext.VerifiedIssuerAndSubject verified = requirePermission(authentication, correlationId);

        ActivateBreakGlassCommand command = new ActivateBreakGlassCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), request.sessionId(), request.scope(),
            request.approvalReference(), request.reason(), request.requiredAssuranceLevel(), request.requiredAssuranceMethods(),
            Duration.ofSeconds(request.ttlSeconds()), correlationId
        );
        BreakGlassGrant grant = manageBreakGlassUseCase.activate(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(BreakGlassGrantView.from(grant));
    }

    @PostMapping("/internal/identity/v1/break-glass/{breakGlassGrantId}/revoke")
    public ResponseEntity<BreakGlassGrantView> revoke(
        @PathVariable String breakGlassGrantId, @RequestBody(required = false) RevokeBreakGlassRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        String correlationId = IdentityRequestContext.correlationId(httpRequest);
        requirePermission(authentication, correlationId);

        String reason = request == null ? null : request.reason();
        RevokeBreakGlassCommand command = new RevokeBreakGlassCommand(
            breakGlassGrantId, IdentityRequestContext.actorId(authentication), reason, correlationId
        );
        return ResponseEntity.ok(BreakGlassGrantView.from(manageBreakGlassUseCase.revoke(command)));
    }

    @GetMapping("/internal/identity/v1/break-glass/{breakGlassGrantId}")
    public ResponseEntity<BreakGlassGrantView> findById(@PathVariable String breakGlassGrantId) {
        return ResponseEntity.ok(BreakGlassGrantView.from(manageBreakGlassUseCase.findById(breakGlassGrantId)));
    }

    private IdentityRequestContext.VerifiedIssuerAndSubject requirePermission(Authentication authentication, String correlationId) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        enforceIdentityPermissionUseCase.require(new RequireIdentityPermissionCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), RolePermissionCatalog.USER_ADMIN, correlationId
        ));
        return verified;
    }
}
