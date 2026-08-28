package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.command.RequireRoleGrantWithinScopeCommand;

/** SPEC-UA-011 (Role And Permission Model) — the real per-endpoint RBAC gate {@code SecurityConfig}'s own javadoc points to. */
public interface EnforceIdentityPermissionUseCase {

    /** @throws com.opsmind.identity.application.exception.PermissionDeniedException when the caller cannot be shown to hold {@code requiredPermission}. */
    void require(RequireIdentityPermissionCommand command);

    /**
     * SPEC-UA-012 (02-business-invariants #9: "A role grantor cannot
     * delegate beyond its own grant scope").
     * @throws com.opsmind.identity.application.exception.RoleGrantOverreachException when granting {@code targetRoleCode} would hand out a permission the grantor does not itself currently hold.
     */
    void requireGrantWithinScope(RequireRoleGrantWithinScopeCommand command);
}
