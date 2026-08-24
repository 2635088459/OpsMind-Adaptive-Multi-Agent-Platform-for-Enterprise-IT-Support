package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.user.IdentityType;

/**
 * {@code issuer}/{@code subject} must come only from the caller's own
 * verified JWT ({@code Jwt#getIssuer()}/{@code Jwt#getSubject()}), never
 * request-body input — see {@code api.internal.UserIdentityController} —
 * so this command can never register an identity under a forged subject.
 */
public record LinkUserIdentityCommand(
    String tenantId,
    String issuer,
    String subject,
    String username,
    String displayName,
    String email,
    IdentityType identityType,
    String correlationId
) {
}
