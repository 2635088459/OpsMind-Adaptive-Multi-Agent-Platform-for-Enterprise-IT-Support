package com.opsmind.identity.application.exception;

/**
 * 02-business-invariants #5 (deny by default): thrown when the caller's own
 * currently-active {@code RoleAssignment}s do not grant the required
 * identity-level permission (SPEC-UA-011).
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String subject, String requiredPermission) {
        super("subject " + subject + " has no active role assignment granting " + requiredPermission);
    }
}
