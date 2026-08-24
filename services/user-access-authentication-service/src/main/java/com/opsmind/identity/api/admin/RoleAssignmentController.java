package com.opsmind.identity.api.admin;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.dto.GrantRoleAssignmentRequest;
import com.opsmind.identity.application.dto.RoleAssignmentView;
import com.opsmind.identity.application.port.in.ManageRoleAssignmentUseCase;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.role.RoleAssignment;
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

/** 05-api-contracts {@code POST /role-assignments} ({@code identity:role:grant}), {@code DELETE /role-assignments/{id}} ({@code identity:role:revoke}). */
@RestController
public class RoleAssignmentController {

    private final ManageRoleAssignmentUseCase manageRoleAssignmentUseCase;

    public RoleAssignmentController(ManageRoleAssignmentUseCase manageRoleAssignmentUseCase) {
        this.manageRoleAssignmentUseCase = manageRoleAssignmentUseCase;
    }

    @PostMapping("/internal/identity/v1/role-assignments")
    public ResponseEntity<RoleAssignmentView> grant(
        @Valid @RequestBody GrantRoleAssignmentRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        GrantRoleAssignmentCommand command = new GrantRoleAssignmentCommand(
            request.userIdentityId(), request.tenantId(), request.roleCode(), request.scope(), request.permissions(),
            request.validUntil(), IdentityRequestContext.actorId(authentication), request.grantReason(),
            IdentityRequestContext.correlationId(httpRequest)
        );
        RoleAssignment saved = manageRoleAssignmentUseCase.grant(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(RoleAssignmentView.from(saved));
    }

    @DeleteMapping("/internal/identity/v1/role-assignments/{roleAssignmentId}")
    public ResponseEntity<RoleAssignmentView> revoke(
        @PathVariable String roleAssignmentId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        RevokeRoleAssignmentCommand command = new RevokeRoleAssignmentCommand(
            roleAssignmentId, IdentityRequestContext.actorId(authentication), "revoked by admin", IdentityRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(RoleAssignmentView.from(manageRoleAssignmentUseCase.revoke(command)));
    }

    @GetMapping("/internal/identity/v1/users/{userIdentityId}/role-assignments")
    public ResponseEntity<List<RoleAssignmentView>> listForUser(@PathVariable String userIdentityId) {
        List<RoleAssignmentView> views = manageRoleAssignmentUseCase.listForUser(new ListRoleAssignmentsQuery(userIdentityId))
            .stream().map(RoleAssignmentView::from).toList();
        return ResponseEntity.ok(views);
    }
}
