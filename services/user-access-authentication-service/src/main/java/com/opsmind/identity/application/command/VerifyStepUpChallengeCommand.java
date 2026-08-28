package com.opsmind.identity.application.command;

import java.util.List;

/**
 * SPEC-UA-018 (Step Up Proof Verification). Every field here is real IdP
 * evidence extracted from the caller's own already-verified re-authentication
 * (an {@code OidcUser} from a fresh Keycloak login round trip forced via
 * {@code prompt=login}), never a client-asserted opaque proof: {@code
 * issuer}/{@code subject} prove WHO re-authenticated (INV-UA-005: "binds
 * issuer, subject, session"); {@code acr}/{@code amr} are the actual
 * assurance the re-authentication achieved (compared against the challenge's
 * own {@code requiredAssuranceLevel}/{@code requiredMethods}); {@code
 * rawNonce} is compared against the challenge's own stored {@code
 * nonceHash} — proof this specific browser round trip belongs to this
 * specific challenge, not a different one.
 */
public record VerifyStepUpChallengeCommand(
    String stepUpChallengeId,
    String issuer,
    String subject,
    String acr,
    List<String> amr,
    String rawNonce,
    String correlationId
) {
}
