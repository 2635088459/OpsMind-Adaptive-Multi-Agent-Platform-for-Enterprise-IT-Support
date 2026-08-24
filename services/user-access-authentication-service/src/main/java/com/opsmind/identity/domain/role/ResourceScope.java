package com.opsmind.identity.domain.role;

/**
 * 01-domain-model §Value Objects. {@code scopeId} narrows {@code TENANT}
 * (unused, tenant is already the aggregate's own tenantId), {@code
 * SUPPORT_QUEUE} (a queue name/id), and {@code RESOURCE} (a specific
 * resource id) — {@code null} for {@code SELF} and for a tenant-wide grant.
 * The richer tenant/queue scope model is SPEC-UA-013's job.
 */
public record ResourceScope(ScopeType scopeType, String scopeId) {

    public enum ScopeType {
        SELF,
        TENANT,
        SUPPORT_QUEUE,
        RESOURCE
    }

    public ResourceScope {
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType must not be null");
        }
    }

    public static ResourceScope tenantWide() {
        return new ResourceScope(ScopeType.TENANT, null);
    }
}
