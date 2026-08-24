package com.opsmind.identity.domain.workload;

/**
 * 03-state-machine §ServiceIdentity.
 *
 * <pre>ACTIVE --disable--&gt; DISABLED --retire--&gt; RETIRED (final)</pre>
 *
 * Reconciliation may also retire an {@code ACTIVE} identity directly once past its own {@code validUntil}.
 */
public enum ServiceIdentityStatus {
    ACTIVE,
    DISABLED,
    RETIRED
}
