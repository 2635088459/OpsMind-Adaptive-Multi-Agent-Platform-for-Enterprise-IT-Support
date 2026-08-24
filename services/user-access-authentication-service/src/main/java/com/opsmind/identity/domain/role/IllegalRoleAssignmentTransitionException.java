package com.opsmind.identity.domain.role;

import com.opsmind.identity.domain.shared.DomainException;

/** Thrown by {@link RoleAssignment} for any transition 03-state-machine §RoleAssignment does not allow. */
public class IllegalRoleAssignmentTransitionException extends DomainException {

    private final RoleAssignmentStatus from;
    private final RoleAssignmentStatus to;

    public IllegalRoleAssignmentTransitionException(RoleAssignmentStatus from, RoleAssignmentStatus to) {
        super("cannot transition role assignment from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public RoleAssignmentStatus from() {
        return from;
    }

    public RoleAssignmentStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "ROLE_ASSIGNMENT_ILLEGAL_TRANSITION";
    }
}
