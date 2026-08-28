package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.CancelRoleAssignmentCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.command.RequireRoleGrantWithinScopeCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.dto.CancelRoleAssignmentRequest;
import com.opsmind.identity.application.dto.GrantRoleAssignmentRequest;
import com.opsmind.identity.application.dto.RoleAssignmentView;
import com.opsmind.identity.application.port.in.EnforceIdentityPermissionUseCase;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 05-api-contracts {@code POST /role-assignments} ({@code identity:role:grant}),
 * {@code DELETE /role-assignments/{id}} ({@code identity:role:revoke}).
 * SPEC-UA-011: both now enforce that authority for real via {@link
 * EnforceIdentityPermissionUseCase} — the caller's own currently-active role
 * assignments, resolved from its verified JWT, not just "authenticated."
 * SPEC-UA-012: {@link #grant} additionally requires the grantor's own scope
 * to cover the role being granted (02-business-invariants #9).
 */
@RestController
public class RoleAssignmentController {

    private final ManageRoleAssignmentUseCase manageRoleAssignmentUseCase;
    private final EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public RoleAssignmentController(
        ManageRoleAssignmentUseCase manageRoleAssignmentUseCase, EnforceIdentityPermissionUseCase enforceIdentityPermissionUseCase,
        BrowserLoginProperties browserLoginProperties
    ) {
        this.manageRoleAssignmentUseCase = manageRoleAssignmentUseCase;
        this.enforceIdentityPermissionUseCase = enforceIdentityPermissionUseCase;
        this.browserLoginProperties = browserLoginProperties;
    }

    @PostMapping("/internal/identity/v1/role-assignments")
    public ResponseEntity<RoleAssignmentView> grant(
        @Valid @RequestBody GrantRoleAssignmentRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        String correlationId = IdentityRequestContext.correlationId(httpRequest);
        requirePermission(authentication, RolePermissionCatalog.ROLE_GRANT, correlationId);
        requireGrantWithinScope(authentication, request.roleCode(), correlationId);
        GrantRoleAssignmentCommand command = new GrantRoleAssignmentCommand(
            request.userIdentityId(), request.tenantId(), request.roleCode(), request.scope(),
            request.validFrom(), request.validUntil(), IdentityRequestContext.actorId(authentication), request.grantReason(), correlationId
        );
        RoleAssignment saved = manageRoleAssignmentUseCase.grant(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentView.from(saved));
    }

    @DeleteMapping("/internal/identity/v1/role-assignments/{roleAssignmentId}")
    public ResponseEntity<RoleAssignmentView> revoke(
        @PathVariable String roleAssignmentId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        String correlationId = IdentityRequestContext.correlationId(httpRequest);
        requirePermission(authentication, RolePermissionCatalog.ROLE_REVOKE, correlationId);
        RevokeRoleAssignmentCommand command = new RevokeRoleAssignmentCommand(
            roleAssignmentId, IdentityRequestContext.actorId(authentication), "revoked by admin", correlationId
        );
        return ResponseEntity.ok(RoleAssignmentView.from(manageRoleAssignmentUseCase.revoke(command)));
    }

    private void requirePermission(Authentication authentication, String requiredPermission, String correlationId) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        enforceIdentityPermissionUseCase.require(new RequireIdentityPermissionCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), requiredPermission, correlationId
        ));
    }

    private void requireGrantWithinScope(Authentication authentication, RoleCode targetRoleCode, String correlationId) {
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        enforceIdentityPermissionUseCase.requireGrantWithinScope(new RequireRoleGrantWithinScopeCommand(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(), targetRoleCode, correlationId
        ));
    }

    /** 03-state-machine §RoleAssignment: {@code PENDING --cancel--> CANCELLED} — withdraws a grant before it ever took effect. */
    @PostMapping("/internal/identity/v1/role-assignments/{roleAssignmentId}/cancel")
    public ResponseEntity<RoleAssignmentView> cancel(
        @PathVariable String roleAssignmentId, @RequestBody(required = false) CancelRoleAssignmentRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        String reason = request == null ? null : request.reason();
        CancelRoleAssignmentCommand command = new CancelRoleAssignmentCommand(
            roleAssignmentId, IdentityRequestContext.actorId(authentication), reason, IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(RoleAssignmentView.from(manageRoleAssignmentUseCase.cancel(command)));
    }

    @GetMapping("/internal/identity/v1/users/{userIdentityId}/role-assignments")
    public ResponseEntity<List<RoleAssignmentView>> listForUser(@PathVariable String userIdentityId) {
        List<RoleAssignmentView> views = manageRoleAssignmentUseCase.listForUser(new ListRoleAssignmentsQuery(userIdentityId))
            .stream().map(RoleAssignmentView::from).toList();
        return ResponseEntity.ok(views);
    }
}
