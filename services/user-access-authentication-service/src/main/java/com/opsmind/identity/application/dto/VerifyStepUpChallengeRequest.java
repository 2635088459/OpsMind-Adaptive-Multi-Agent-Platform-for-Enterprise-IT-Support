package com.opsmind.identity.application.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyStepUpChallengeRequest(
    @NotBlank String proofIdHash
) {
}
