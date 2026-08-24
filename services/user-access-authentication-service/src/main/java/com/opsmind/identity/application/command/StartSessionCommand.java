package com.opsmind.identity.application.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** {@code issuer}/{@code subject} come only from the caller's own verified JWT, mirroring {@code LinkUserIdentityCommand}. */
public record StartSessionCommand(
    String tenantId,
    String issuer,
    String subject,
    String idpSessionIdHash,
    String tokenIdHash,
    String clientId,
    String acr,
    List<String> amr,
    Instant authTime,
    String deviceIdHash,
    Duration ttl,
    String correlationId
) {
}
