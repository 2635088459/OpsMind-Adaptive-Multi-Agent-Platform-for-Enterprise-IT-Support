package com.opsmind.identity.domain.breakglass;

/**
 * SPEC-UA-019 (Break Glass And Account Recovery — 04-use-cases §Break-glass:
 * "Auto-expire and emit high-priority audit").
 *
 * <pre>ACTIVE --expire(bounded time reached)--> EXPIRED
 * ACTIVE --revoke--> REVOKED (final)</pre>
 */
public enum BreakGlassStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
