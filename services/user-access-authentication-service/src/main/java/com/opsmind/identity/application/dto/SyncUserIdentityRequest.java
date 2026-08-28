package com.opsmind.identity.application.dto;

/**
 * SPEC-UA-008 (04-use-cases §User synchronization: "IdP event/admin |
 * Upsert minimum profile by issuer+subject"). This is the admin-triggered
 * half — {@code ProvisionUserUseCase#sync} itself already protects against
 * a stale {@code profileVersion} overwriting a newer one
 * (10-failure-handling: "upstream version/time prevents stale overwrite").
 * The IdP-event-triggered half (a real Keycloak/admin-adapter consumer,
 * 06-event-contracts §Consumed events) is deliberately deferred: no
 * concrete queue name, event type, or payload schema for that category is
 * specified anywhere in this domain's LLD, and no producer of it exists
 * anywhere in this repository to build or verify a real consumer against —
 * inventing one now would be a guess a later spec would likely have to
 * redo, not a real implementation.
 */
public record SyncUserIdentityRequest(String username, String displayName, String email, long profileVersion) {
}
