package com.opsmind.identity.application.command;

public record DisableServiceIdentityCommand(
    String serviceIdentityId,
    String correlationId
) {
}
