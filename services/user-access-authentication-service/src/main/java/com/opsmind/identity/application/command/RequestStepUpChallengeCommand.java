package com.opsmind.identity.application.command;

import java.time.Duration;
import java.util.List;

public record RequestStepUpChallengeCommand(
    String userSessionId,
    String action,
    String resourceType,
    String resourceId,
    String requiredAssuranceLevel,
    List<String> requiredMethods,
    int maxAttempts,
    Duration ttl,
    String correlationId
) {
}
