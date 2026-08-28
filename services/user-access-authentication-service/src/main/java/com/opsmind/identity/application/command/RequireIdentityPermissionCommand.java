package com.opsmind.identity.application.command;

/**
 * SPEC-UA-011 (Role And Permission Model). {@code issuer}/{@code subject}
 * always come from the caller's own already-verified JWT (02-business-invariants
 * #7) — never a request-body field.
 */
public record RequireIdentityPermissionCommand(
    String tenantId,
    String issuer,
    String subject,
    String requiredPermission,
    String correlationId
) {
}
