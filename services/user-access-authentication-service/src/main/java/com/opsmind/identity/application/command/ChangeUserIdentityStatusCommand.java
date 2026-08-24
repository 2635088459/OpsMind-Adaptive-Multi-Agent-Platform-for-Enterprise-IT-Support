package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.user.UserStatus;

/** 05-api-contracts {@code PUT /users/{id}/status}. */
public record ChangeUserIdentityStatusCommand(
    String userIdentityId,
    UserStatus targetStatus,
    String reason,
    String correlationId
) {
}
