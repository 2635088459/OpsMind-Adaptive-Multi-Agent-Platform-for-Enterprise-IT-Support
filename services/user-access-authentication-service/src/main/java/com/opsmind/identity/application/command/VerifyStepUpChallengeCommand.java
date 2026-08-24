package com.opsmind.identity.application.command;

public record VerifyStepUpChallengeCommand(
    String stepUpChallengeId,
    String proofIdHash,
    String correlationId
) {
}
