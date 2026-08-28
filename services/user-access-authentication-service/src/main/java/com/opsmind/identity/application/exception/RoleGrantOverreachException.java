package com.opsmind.identity.application.exception;

import com.opsmind.identity.domain.role.RoleCode;

/**
 * 02-business-invariants #9: "A role grantor cannot delegate beyond its own
 * grant scope" — thrown when the target role's own {@code
 * RolePermissionCatalog} permission set is not fully covered by the
 * grantor's own currently-active role assignments' permissions
 * (04-use-cases §Grant/revoke role: "Overreach ... returns 403").
 */
public class RoleGrantOverreachException extends RuntimeException {

    public RoleGrantOverreachException(String subject, RoleCode targetRoleCode) {
        super("subject " + subject + " cannot grant role " + targetRoleCode + ": it exceeds the grantor's own grant scope");
    }
}
