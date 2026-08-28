package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleCode;

/** SPEC-UA-007 (05-api-contracts {@code GET /users/me}: "effective roles/scopes"). */
public record EffectiveRoleView(RoleCode roleCode, ResourceScope scope) {

    public static EffectiveRoleView from(RoleAssignment a) {
        return new EffectiveRoleView(a.roleCode(), a.scope());
    }
}
