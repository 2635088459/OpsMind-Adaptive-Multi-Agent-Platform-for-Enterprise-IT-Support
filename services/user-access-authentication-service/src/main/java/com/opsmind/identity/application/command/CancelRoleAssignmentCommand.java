package com.opsmind.identity.application.command;

/** 03-state-machine §RoleAssignment: {@code PENDING --cancel--> CANCELLED}. */
public record CancelRoleAssignmentCommand(
    String roleAssignmentId,
    String cancelledBy,
    String reason,
    String correlationId
) {
}
