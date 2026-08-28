package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 05-api-contracts {@code POST /role-assignments}: "userId, roleCode, scopeType/id, validFrom/until, reason" — no {@code permissions} field (SPEC-UA-011: server-derived from {@code roleCode}, never client-supplied). */
public record GrantRoleAssignmentRequest(
    @NotBlank String userIdentityId,
    @NotBlank String tenantId,
    @NotNull RoleCode roleCode,
    @NotNull ResourceScope scope,
    // null or not in the future grants immediately; a future instant schedules a PENDING grant (SPEC-UA-012).
    Instant validFrom,
    Instant validUntil,
    String grantReason
) {
}
