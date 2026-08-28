package com.opsmind.identity.application.command;

/** SPEC-UA-017 (Step Up Challenge Lifecycle — 03-state-machine §StepUpChallenge: {@code PENDING --cancel--> CANCELLED}). */
public record CancelStepUpChallengeCommand(
    String stepUpChallengeId,
    String correlationId
) {
}
