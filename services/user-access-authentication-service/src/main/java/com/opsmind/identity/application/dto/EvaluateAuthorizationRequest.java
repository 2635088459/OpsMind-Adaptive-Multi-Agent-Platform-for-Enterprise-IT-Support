package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 05-api-contracts {@code POST /authorization-decisions}: "principalRef, action, resource, ownershipContext, requiredAssurance" — {@code resourceOwnerId} is that request's own {@code ownershipContext} (SPEC-UA-015); {@code requiredAssuranceLevel}/{@code requiredAssuranceMethods} are its own {@code requiredAssurance} (SPEC-UA-016). */
public record EvaluateAuthorizationRequest(
    @NotBlank String tenantId,
    @NotBlank String subjectId,
    String sessionId,
    @NotBlank String action,
    String resourceType,
    String resourceId,
    RoleCode requiredRole,
    ResourceScope requiredScope,
    String resourceOwnerId,
    String requiredAssuranceLevel,
    List<String> requiredAssuranceMethods
) {
}
