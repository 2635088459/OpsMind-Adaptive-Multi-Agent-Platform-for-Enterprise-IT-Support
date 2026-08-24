package com.opsmind.identity.domain.stepup;

/** 01-domain-model §Value Objects. What a step-up (or authorization decision) is being requested for. */
public record AuthorizationTarget(String action, String resourceType, String resourceId) {

    public AuthorizationTarget {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }
}
