package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record ActivateBreakGlassRequest(
    @NotBlank String sessionId,
    @NotNull ResourceScope scope,
    @NotBlank String approvalReference,
    @NotBlank String reason,
    String requiredAssuranceLevel,
    List<String> requiredAssuranceMethods,
    @Positive long ttlSeconds
) {
}
