package com.opsmind.identity.application.command;

import java.util.List;

/**
 * SPEC-UA-010 (11-security §Tokens and protocols: "Workloads use client
 * credentials or mTLS with separate audiences/scopes and cannot impersonate
 * a human sub"). {@code issuer}/{@code subject}/{@code tokenAudiences}/
 * {@code tokenScopes} always come from the caller's own already-verified
 * {@link org.springframework.security.oauth2.jwt.Jwt} (02-business-invariants
 * #7) — never client-supplied request fields.
 */
public record ValidateWorkloadIdentityCommand(
    String tenantId,
    String issuer,
    String subject,
    List<String> tokenAudiences,
    List<String> tokenScopes,
    String correlationId
) {
}
