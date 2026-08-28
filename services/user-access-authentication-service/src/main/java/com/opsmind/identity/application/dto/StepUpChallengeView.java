package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;

import java.time.Instant;

/** {@code redirect} (05-api-contracts {@code POST /step-up/challenges}: "returns challengeId, redirect, expiresAt") is only ever populated on the response to a fresh {@code request()} call — SPEC-UA-018. */
public record StepUpChallengeView(
    String stepUpChallengeId,
    String userSessionId,
    String action,
    String resourceType,
    String resourceId,
    StepUpStatus status,
    int attemptCount,
    int maxAttempts,
    Instant createdAt,
    Instant expiresAt,
    Instant verifiedAt,
    Instant consumedAt,
    String redirect
) {
    public static StepUpChallengeView from(StepUpChallenge c) {
        return from(c, null);
    }

    public static StepUpChallengeView from(StepUpChallenge c, String redirect) {
        return new StepUpChallengeView(
            c.stepUpChallengeId(), c.userSessionId(), c.target().action(), c.target().resourceType(), c.target().resourceId(),
            c.status(), c.attemptCount(), c.maxAttempts(), c.createdAt(), c.expiresAt(), c.verifiedAt(), c.consumedAt(), redirect
        );
    }
}
