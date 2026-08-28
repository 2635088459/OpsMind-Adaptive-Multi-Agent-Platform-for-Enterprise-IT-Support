package com.opsmind.identity.application.command;

import java.time.Duration;
import java.util.List;

/**
 * SPEC-UA-018 (Step Up Proof Verification): {@code nonceHash} is computed
 * server-side by the caller (the controller — mirroring how {@code
 * BrowserLoginSuccessHandler} already hashes the session {@code sid} before
 * it ever reaches an application command) from a real, cryptographically
 * random raw nonce; only the hash is ever persisted (INV-UA-001's own
 * spirit — never store the original of anything hashed). The raw value is
 * embedded in the {@code redirect} URI returned to the caller so the
 * eventual step-up callback can present it back as real evidence.
 */
public record RequestStepUpChallengeCommand(
    String userSessionId,
    String action,
    String resourceType,
    String resourceId,
    String requiredAssuranceLevel,
    List<String> requiredMethods,
    int maxAttempts,
    Duration ttl,
    String nonceHash,
    String correlationId
) {
}
