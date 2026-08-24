package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;

/**
 * 05-api-contracts {@code POST /authorization-decisions}. {@code
 * requiredRole}/{@code requiredScope} are nullable: when absent, evaluation
 * only checks the subject's {@code UserIdentity} is trusted and {@code
 * ACTIVE} (02-business-invariants #5's "trusted principal" leg) — the full
 * role/scope/ownership/assurance intersection algorithm is SPEC-UA-014's job.
 */
public record EvaluateAuthorizationCommand(
    String tenantId,
    String actorId,
    String subjectId,
    String sessionId,
    String action,
    String resourceType,
    String resourceId,
    RoleCode requiredRole,
    ResourceScope requiredScope,
    String correlationId
) {
}
