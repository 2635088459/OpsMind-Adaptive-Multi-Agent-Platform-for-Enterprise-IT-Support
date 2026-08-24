package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;

import java.time.Instant;

public record UserIdentityView(
    String userIdentityId,
    String tenantId,
    String issuer,
    String subject,
    String username,
    String displayName,
    String email,
    IdentityType identityType,
    UserStatus status,
    long profileVersion,
    Instant linkedAt,
    Instant lastSyncedAt,
    Instant updatedAt
) {
    public static UserIdentityView from(UserIdentity u) {
        return new UserIdentityView(
            u.userIdentityId(), u.tenantId().value(), u.externalSubject().issuer(), u.externalSubject().subject(),
            u.username(), u.displayName(), u.email(), u.identityType(), u.status(), u.profileVersion(),
            u.linkedAt(), u.lastSyncedAt(), u.updatedAt()
        );
    }
}
