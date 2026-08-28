package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;

import java.util.List;

/** SPEC-UA-007 (05-api-contracts {@code GET /users/me}: "Minimum profile plus effective roles/scopes"). */
public record MyProfileView(
    String userIdentityId,
    String tenantId,
    String issuer,
    String subject,
    String username,
    String displayName,
    String email,
    IdentityType identityType,
    UserStatus status,
    List<EffectiveRoleView> effectiveRoles
) {
    public static MyProfileView of(UserIdentity u, List<EffectiveRoleView> effectiveRoles) {
        return new MyProfileView(
            u.userIdentityId(), u.tenantId().value(), u.externalSubject().issuer(), u.externalSubject().subject(),
            u.username(), u.displayName(), u.email(), u.identityType(), u.status(), effectiveRoles
        );
    }
}
