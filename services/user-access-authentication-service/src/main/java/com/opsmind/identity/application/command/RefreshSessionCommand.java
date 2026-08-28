package com.opsmind.identity.application.command;

/** SPEC-UA-009 (Session Refresh Logout And Revocation). Updates {@code lastSeenAt} only — legal only from {@code ACTIVE}, never undoes revocation. */
public record RefreshSessionCommand(String userSessionId, String correlationId) {
}
