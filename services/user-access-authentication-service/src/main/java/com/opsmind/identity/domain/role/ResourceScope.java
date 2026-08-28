package com.opsmind.identity.domain.role;

/**
 * 01-domain-model §Value Objects. {@code scopeId} narrows {@code
 * SUPPORT_QUEUE} (a queue name/id) and {@code RESOURCE} (a specific
 * resource id) — always {@code null} for {@code SELF} and {@code TENANT}
 * ({@code TENANT} needs no {@code scopeId} at all: tenant is already the
 * aggregate's own {@code tenantId}).
 *
 * <p>SPEC-UA-013 (Tenant And Support Queue Scope — 11-security: "Controller,
 * application use case, and repository query all enforce tenant/scope"):
 * this compact constructor is "the richer tenant/queue scope model" this
 * class's own javadoc used to point at as still-unbuilt — real structural
 * validation instead of accepting any {@code (scopeType, scopeId)}
 * combination, including an invalid one, silently. A matching database
 * CHECK constraint ({@code ck_role_assignments_scope_id_matches_type}) is
 * the same rule enforced again at the repository layer (defense in depth,
 * matching 11-security's own literal wording).
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
        switch (scopeType) {
            case SELF, TENANT -> {
                if (scopeId != null) {
                    throw new IllegalArgumentException(scopeType + " scope must not carry a scopeId");
                }
            }
            case SUPPORT_QUEUE, RESOURCE -> {
                if (scopeId == null || scopeId.isBlank()) {
                    throw new IllegalArgumentException(scopeType + " scope requires a non-blank scopeId");
                }
            }
        }
    }

    public static ResourceScope tenantWide() {
        return new ResourceScope(ScopeType.TENANT, null);
    }

    /**
     * SPEC-UA-014 (Authorization Context And Decision API — {@code
     * AuthorizationDecision}'s own javadoc: "The actual evaluation algorithm
     * (role/scope/... intersection) is SPEC-UA-014's job"). Whether a role
     * assignment granted at this scope authorizes an action that requires
     * {@code required}. A {@code null} required scope means no scope
     * restriction applies at all (mirrors {@code EvaluateAuthorizationCommand
     * #requiredRole}'s own "absent means unrestricted" convention). A {@code
     * TENANT}-wide grant covers every narrower organizational scope except
     * {@code SELF} — ownership is a wholly different axis (SPEC-UA-015's own
     * job), never satisfied merely by a broader organizational grant. Every
     * other combination requires an exact match: domain 01 has no
     * cross-domain knowledge letting it decide, for example, whether a given
     * {@code RESOURCE} belongs to a specific {@code SUPPORT_QUEUE} (that
     * mapping is owned entirely by another domain).
     */
    public boolean covers(ResourceScope required) {
        if (required == null) {
            return true;
        }
        if (this.scopeType == ScopeType.TENANT && required.scopeType != ScopeType.SELF) {
            return true;
        }
        return this.equals(required);
    }
}
