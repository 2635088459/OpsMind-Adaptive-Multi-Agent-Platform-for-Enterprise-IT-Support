package com.opsmind.identity.application.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 05-api-contracts {@code POST /step-up/challenges/{id}/verify}: "IdP
 * evidence" (SPEC-UA-018). For a non-browser trusted-workload caller that
 * already completed its own re-authentication and holds the resulting
 * verified claims directly — the browser flow itself never calls this
 * endpoint at all; {@code StepUpVerificationSuccessHandler} invokes {@code
 * ManageStepUpUseCase#verify} in-process with the same evidence shape.
 */
public record VerifyStepUpChallengeRequest(
    @NotBlank String issuer,
    @NotBlank String subject,
    String acr,
    List<String> amr,
    @NotBlank String nonce
) {
}
