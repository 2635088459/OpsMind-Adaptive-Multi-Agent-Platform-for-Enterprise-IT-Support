package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;

import java.time.Instant;

public record UserSessionView(
    String userSessionId,
    String tenantId,
    String issuer,
    String subject,
    SessionStatus status,
    Instant startedAt,
    Instant lastSeenAt,
    Instant expiresAt,
    Instant revokedAt,
    String revocationReason
) {
    public static UserSessionView from(UserSession s) {
        return new UserSessionView(
            s.userSessionId(), s.tenantId().value(), s.externalSubject().issuer(), s.externalSubject().subject(),
            s.status(), s.startedAt(), s.lastSeenAt(), s.expiresAt(), s.revokedAt(), s.revocationReason()
        );
    }
}
