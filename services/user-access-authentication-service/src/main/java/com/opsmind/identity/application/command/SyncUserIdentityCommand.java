package com.opsmind.identity.application.command;

/** 04-use-cases §User Synchronization. */
public record SyncUserIdentityCommand(
    String userIdentityId,
    String username,
    String displayName,
    String email,
    long profileVersion,
    String correlationId
) {
}
