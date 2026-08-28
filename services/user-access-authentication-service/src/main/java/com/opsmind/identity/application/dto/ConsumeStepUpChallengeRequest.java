package com.opsmind.identity.application.dto;

import jakarta.validation.constraints.NotBlank;

/** 05-api-contracts {@code POST /step-up/proofs/{handle}/consume}: "action/resource/correlation" (SPEC-UA-017). */
public record ConsumeStepUpChallengeRequest(
    @NotBlank String action,
    String resourceType,
    String resourceId
) {
}
