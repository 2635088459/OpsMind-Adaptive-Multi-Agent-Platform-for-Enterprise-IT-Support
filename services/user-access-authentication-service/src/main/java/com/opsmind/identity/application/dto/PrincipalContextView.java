package com.opsmind.identity.application.dto;

import java.time.Instant;
import java.util.List;

/** SPEC-UA-007 (05-api-contracts {@code POST /tokens/introspect-context}: "returns normalized principal, assurance, session status"). */
public record PrincipalContextView(
    String tenantId,
    String issuer,
    String subject,
    String userIdentityId,
    String identityStatus,
    String acr,
    List<String> amr,
    Instant authTime,
    String userSessionId,
    String sessionStatus
) {
}
