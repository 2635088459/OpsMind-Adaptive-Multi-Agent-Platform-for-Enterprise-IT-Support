package dev.opsmind.ticketworkflow.ticket.application.command;

import java.time.Instant;

/**
 * Step-up authentication proof (SPEC-TW-036 persistence: "Step-up proof
 * stores only proof id, method, verifiedAt, and expiresAt, not
 * authentication material"). The actual step-up ceremony (MFA challenge,
 * WebAuthn, re-authentication) happens entirely in the identity provider
 * (SPEC-TW-036 §2 excludes "replacing baseline Keycloak/OAuth2
 * authentication"); this service only ever sees this redacted, already-
 * asserted evidence — via trusted JWT claims for a human-facing command
 * ({@code CancelTicketController}/{@code EscalateTicketController}), or via
 * the request body of the internal policy-evaluate endpoint, where the
 * caller and the proof's subject are different principals. {@code null}
 * fields mean the claim/field was absent, not that step-up was denied —
 * {@link dev.opsmind.ticketworkflow.ticket.application.policy.StepUpAuthenticationPolicy}
 * is the single place that turns an absent or malformed proof into a
 * decision.
 */
public record StepUpProof(
    String proofId,
    String method,
    Instant verifiedAt,
    Instant expiresAt
) {
}
