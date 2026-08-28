package com.opsmind.identity.application.query;

import java.time.Instant;
import java.util.List;

/**
 * SPEC-UA-007 (05-api-contracts {@code POST /tokens/introspect-context}).
 * Every field but {@code userSessionId} comes only from the caller's own
 * verified {@code Jwt} (never request-body input) — mirrors {@code
 * LinkUserIdentityCommand}'s own javadoc for why that makes this
 * unspoofable. {@code userSessionId} is the one caller-supplied field
 * (e.g. the opaque session id SPEC-UA-005's login cookie carries) and is
 * only ever used to look up a session already scoped to this same
 * verified {@code (tenantId, issuer, subject)} — see {@code
 * IntrospectPrincipalService}'s own javadoc.
 */
public record IntrospectPrincipalContextQuery(
    String tenantId,
    String issuer,
    String subject,
    String acr,
    List<String> amr,
    Instant authTime,
    String userSessionId
) {
}
