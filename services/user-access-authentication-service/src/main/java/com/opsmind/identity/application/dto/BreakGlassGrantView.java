package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.breakglass.BreakGlassStatus;

import java.time.Instant;

public record BreakGlassGrantView(
    String breakGlassGrantId,
    String scopeType,
    String scopeId,
    String reason,
    BreakGlassStatus status,
    Instant grantedAt,
    Instant expiresAt,
    Instant revokedAt
) {
    public static BreakGlassGrantView from(BreakGlassGrant g) {
        return new BreakGlassGrantView(
            g.breakGlassGrantId(), g.scope().scopeType().name(), g.scope().scopeId(), g.reason(), g.status(),
            g.grantedAt(), g.expiresAt(), g.revokedAt()
        );
    }
}
