package com.opsmind.identity.domain.role;

/**
 * 03-state-machine §RoleAssignment.
 *
 * <pre>
 *   PENDING --activate(validFrom)--> ACTIVE --revoke--> REVOKED
 *   PENDING --cancel--> CANCELLED
 *   ACTIVE --validUntil reached--> EXPIRED
 * </pre>
 *
 * {@code REVOKED}, {@code EXPIRED}, and {@code CANCELLED} are final.
 */
public enum RoleAssignmentStatus {
    PENDING,
    ACTIVE,
    REVOKED,
    EXPIRED,
    CANCELLED
}
