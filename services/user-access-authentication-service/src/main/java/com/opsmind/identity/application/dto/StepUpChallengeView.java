package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;

import java.time.Instant;

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
    Instant consumedAt
) {
    public static StepUpChallengeView from(StepUpChallenge c) {
        return new StepUpChallengeView(
            c.stepUpChallengeId(), c.userSessionId(), c.target().action(), c.target().resourceType(), c.target().resourceId(),
            c.status(), c.attemptCount(), c.maxAttempts(), c.createdAt(), c.expiresAt(), c.verifiedAt(), c.consumedAt()
        );
    }
}
