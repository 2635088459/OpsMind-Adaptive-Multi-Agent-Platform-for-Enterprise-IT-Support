package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import jakarta.validation.constraints.NotBlank;

public record EvaluateAuthorizationRequest(
    @NotBlank String tenantId,
    @NotBlank String subjectId,
    String sessionId,
    @NotBlank String action,
    String resourceType,
    String resourceId,
    RoleCode requiredRole,
    ResourceScope requiredScope
) {
}
