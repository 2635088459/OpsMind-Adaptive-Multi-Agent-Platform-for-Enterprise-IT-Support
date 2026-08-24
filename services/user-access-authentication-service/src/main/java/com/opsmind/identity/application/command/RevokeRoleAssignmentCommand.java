package com.opsmind.identity.application.command;

public record RevokeRoleAssignmentCommand(
    String roleAssignmentId,
    String revokedBy,
    String reason,
    String correlationId
) {
}
