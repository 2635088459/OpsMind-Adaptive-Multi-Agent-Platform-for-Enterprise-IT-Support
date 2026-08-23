package com.opsmind.policygovernance.domain.decision;

import java.util.Objects;

/**
 * A downstream restriction a {@link PolicyDecision} or approval attaches to
 * its subject — e.g. read-only, a time window, a retry cap, or a
 * verification requirement (01-domain-model §Value Objects).
 */
public record Constraint(Type type, String detail) {

    public Constraint {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(detail, "detail");
    }

    public enum Type {
        READ_ONLY,
        TIME_WINDOW,
        MAX_RETRY,
        VERIFICATION_REQUIRED
    }
}
