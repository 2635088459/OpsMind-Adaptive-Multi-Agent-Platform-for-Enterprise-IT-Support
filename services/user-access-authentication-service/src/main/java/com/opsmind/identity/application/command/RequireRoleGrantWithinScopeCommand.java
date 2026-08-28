package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.role.RoleCode;

/**
 * SPEC-UA-012 (Role Assignment Lifecycle — 02-business-invariants #9: "A
 * role grantor cannot delegate beyond its own grant scope"). {@code issuer}/
 * {@code subject} are the grantor's own already-verified JWT identity, never
 * a request-body field.
 */
public record RequireRoleGrantWithinScopeCommand(
    String tenantId,
    String issuer,
    String subject,
    RoleCode targetRoleCode,
    String correlationId
) {
}
