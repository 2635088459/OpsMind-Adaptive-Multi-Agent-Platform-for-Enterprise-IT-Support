package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record GrantRoleAssignmentRequest(
    @NotBlank String userIdentityId,
    @NotBlank String tenantId,
    @NotNull RoleCode roleCode,
    @NotNull ResourceScope scope,
    List<String> permissions,
    Instant validUntil,
    String grantReason
) {
}
