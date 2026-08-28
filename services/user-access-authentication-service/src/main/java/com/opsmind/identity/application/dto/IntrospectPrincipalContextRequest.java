package com.opsmind.identity.application.dto;

/** {@code userSessionId} is optional — omit it to introspect only the token-derived principal/assurance, with no session status. */
public record IntrospectPrincipalContextRequest(String userSessionId) {
}
