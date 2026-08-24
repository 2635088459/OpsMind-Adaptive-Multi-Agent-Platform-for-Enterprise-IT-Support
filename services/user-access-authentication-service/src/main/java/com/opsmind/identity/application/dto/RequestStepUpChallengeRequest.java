package com.opsmind.identity.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record RequestStepUpChallengeRequest(
    @NotBlank String userSessionId,
    @NotBlank String action,
    String resourceType,
    String resourceId,
    String requiredAssuranceLevel,
    List<String> requiredMethods,
    int maxAttempts,
    @Positive long ttlSeconds
) {
}
