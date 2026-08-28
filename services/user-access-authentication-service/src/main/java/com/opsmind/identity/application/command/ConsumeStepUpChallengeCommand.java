package com.opsmind.identity.application.command;

/**
 * SPEC-UA-017 (Step Up Challenge Lifecycle — 05-api-contracts {@code POST
 * /step-up/proofs/{handle}/consume}: "action/resource/correlation"): {@code
 * action}/{@code resourceType}/{@code resourceId} are the caller's own
 * assertion of what it is about to do — compared against the challenge's
 * own bound target (INV-UA-005) before consuming, never trusted blindly.
 */
public record ConsumeStepUpChallengeCommand(
    String stepUpChallengeId,
    String action,
    String resourceType,
    String resourceId,
    String correlationId
) {
}
