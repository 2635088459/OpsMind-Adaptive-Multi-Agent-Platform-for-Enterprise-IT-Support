package com.opsmind.identity.domain.user;

/**
 * 03-state-machine §UserIdentity.
 *
 * <pre>
 *   ACTIVE --disable--> DISABLED --enable--> ACTIVE
 *   ACTIVE --deprovision--> DEPROVISIONED (final)
 *   DISABLED --deprovision--> DEPROVISIONED (final)
 * </pre>
 */
public enum UserStatus {
    ACTIVE,
    DISABLED,
    DEPROVISIONED
}
