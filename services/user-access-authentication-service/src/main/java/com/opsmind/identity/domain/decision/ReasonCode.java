package com.opsmind.identity.domain.decision;

/** 01-domain-model §Value Objects. A machine-readable reason attached to an {@link AuthorizationDecision}. */
public record ReasonCode(String value) {

    public ReasonCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reasonCode value must not be blank");
        }
    }
}
