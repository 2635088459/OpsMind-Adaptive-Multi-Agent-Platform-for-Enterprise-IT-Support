package com.opsmind.identity.application.command;

/**
 * SPEC-UA-009 (05-api-contracts {@code POST /sessions/logout}: "Session
 * derived from principal"). {@code idpSessionIdHash} is derived only from
 * the caller's own verified token's {@code sid} claim, never a
 * caller-supplied session id — this is what makes logout unspoofable, the
 * same discipline {@code LinkUserIdentityCommand}'s own javadoc documents
 * for {@code issuer}/{@code subject}.
 */
public record LogoutCommand(String tenantId, String issuer, String subject, String idpSessionIdHash, String correlationId) {
}
