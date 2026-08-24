package com.opsmind.identity.application.command;

import java.time.Instant;
import java.util.List;

public record RegisterServiceIdentityCommand(
    String tenantId,
    String issuer,
    String subject,
    String clientId,
    String serviceName,
    List<String> allowedAudiences,
    List<String> allowedScopes,
    Instant validFrom,
    Instant validUntil,
    String correlationId
) {
}
