package com.opsmind.identity.application.command;

public record ConsumeStepUpChallengeCommand(
    String stepUpChallengeId,
    String correlationId
) {
}
