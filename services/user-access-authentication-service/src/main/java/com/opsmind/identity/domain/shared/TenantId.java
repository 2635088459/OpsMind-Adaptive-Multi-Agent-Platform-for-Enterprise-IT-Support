package com.opsmind.identity.domain.shared;

/** 01-domain-model §Value Objects. Normalizes/validates at construction — never blank. */
public record TenantId(String value) {

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}
