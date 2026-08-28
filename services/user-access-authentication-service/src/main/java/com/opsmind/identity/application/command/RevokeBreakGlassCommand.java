package com.opsmind.identity.application.command;

public record RevokeBreakGlassCommand(
    String breakGlassGrantId,
    String revokedBy,
    String reason,
    String correlationId
) {
}
