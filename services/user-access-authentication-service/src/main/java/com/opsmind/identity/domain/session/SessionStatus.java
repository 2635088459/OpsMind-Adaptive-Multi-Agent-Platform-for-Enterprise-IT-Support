package com.opsmind.identity.domain.session;

/**
 * 03-state-machine §UserSession. Every non-{@code ACTIVE} state is final.
 *
 * <pre>
 *   ACTIVE --expiry--> EXPIRED
 *   ACTIVE --logout/admin revoke--> REVOKED
 *   ACTIVE --security signal--> COMPROMISED
 *   ACTIVE --normal termination--> TERMINATED
 * </pre>
 */
public enum SessionStatus {
    ACTIVE,
    EXPIRED,
    REVOKED,
    COMPROMISED,
    TERMINATED
}
