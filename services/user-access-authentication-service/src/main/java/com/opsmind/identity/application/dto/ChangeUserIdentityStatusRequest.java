package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.user.UserStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeUserIdentityStatusRequest(
    @NotNull UserStatus status,
    String reason
) {
}
