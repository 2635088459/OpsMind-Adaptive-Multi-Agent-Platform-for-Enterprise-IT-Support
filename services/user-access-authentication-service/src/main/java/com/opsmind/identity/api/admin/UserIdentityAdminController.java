package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.command.SyncUserIdentityCommand;
import com.opsmind.identity.application.dto.ChangeUserIdentityStatusRequest;
import com.opsmind.identity.application.dto.SyncUserIdentityRequest;
import com.opsmind.identity.application.dto.UserIdentityView;
import com.opsmind.identity.application.port.in.EnforceIdentityPermissionUseCase;
import com.opsmind.identity.application.port.in.ProvisionUserUseCase;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
import com.opsmind.identity.domain.user.UserIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 05-api-contracts {@code PUT /users/{id}/status} — {@code identity:user:admin};
 * {@code identity:user:admin} again for {@link #sync} (SPEC-UA-008). SPEC-UA-011:
 * both now enforce that authority for real via {@link EnforceIdentityPermissionUseCase}.
 */
@RestController
public class UserIdentityAdminController {

    private final ProvisionUserUseCase provisionUserUseCase;
    private final EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public UserIdentityAdminController(
        ProvisionUserUseCase provisionUserUseCase, EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase,
        BrowserLoginProperties browserLoginProperties
    ) {
        this.provisionUserUseCase = provisionUserUseCase;
        this.enforceIdentityPermissionUseCase = enforceIdentityPermissionUseCase;
        this.browserLoginProperties = browserLoginProperties;
    }

    @PutMapping("/internal/identity/v1/users/{userIdentityId}/status")
    public ResponseEntity<UserIdentityView> changeStatus(
        @PathVariable String userIdentityId, @Valid @RequestBody ChangeUserIdentityStatusRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        String correlationId = IdentityRequestContext.correlationId(httpRequest);
        requirePermission(authentication, correlationId);
        ChangeUserIdentityStatusCommand command = new ChangeUserIdentityStatusCommand(
            userIdentityId, request.status(), request.reason(), correlationId
        );
        UserIdentity updated = provisionUserUseCase.changeStatus(command);
        return ResponseEntity.ok(UserIdentityView.from(updated));
    }

    /**
     * SPEC-UA-008 (04-use-cases §User synchronization, admin-triggered
     * half — see {@code SyncUserIdentityRequest}'s own javadoc for why the
     * IdP-event-triggered half is deferred). Idempotent/no-op if {@code
     * profileVersion} is not newer than what is already stored.
     */
    @PutMapping("/internal/identity/v1/users/{userIdentityId}/profile")
    public ResponseEntity<UserIdentityView> sync(
        @PathVariable String userIdentityId, @RequestBody SyncUserIdentityRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        String correlationId = IdentityRequestContext.correlationId(httpRequest);
        requirePermission(authentication, correlationId);
        SyncUserIdentityCommand command = new SyncUserIdentityCommand(
            userIdentityId, request.username(), request.displayName(), request.email(), request.profileVersion(), correlationId
        );
        UserIdentity synced = provisionUserUseCase.sync(command);
        return ResponseEntity.ok(UserIdentityView.from(synced));
    }

    private void requirePermission(Authentication authentication, String correlationId) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        enforceIdentityPermissionUseCase.require(new RequireIdentityPermissionCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), RolePermissionCatalog.USER_ADMIN, correlationId
        ));
    }
}
