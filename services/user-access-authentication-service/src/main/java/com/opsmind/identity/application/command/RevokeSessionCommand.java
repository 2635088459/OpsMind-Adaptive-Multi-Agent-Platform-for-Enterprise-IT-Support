package com.opsmind.identity.application.command;

public record RevokeSessionCommand(
    String userSessionId,
    String revokedBy,
    String reason,
    String correlationId
) {
}
