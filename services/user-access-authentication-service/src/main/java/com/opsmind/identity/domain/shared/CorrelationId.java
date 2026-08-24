package com.opsmind.identity.domain.shared;

/** 01-domain-model §Value Objects; 02-business-invariants INV-UA-004 requires every security-relevant record to carry one. */
public record CorrelationId(String value) {

    public CorrelationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }
}
