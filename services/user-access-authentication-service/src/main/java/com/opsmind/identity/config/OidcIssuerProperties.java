package com.opsmind.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * SPEC-UA-004 (11-security: "Each issuer has fixed discovery URL, allowed
 * algorithms, audiences, and token types"). {@code allowedAudiences} empty
 * means no audience restriction is enforced — every other Java service in
 * this platform currently validates only issuer/signature/expiry with no
 * audience claim configured on the shared Keycloak realm, so defaulting
 * this to a made-up required value would break real interop rather than
 * harden it; the capability is real, the value is opt-in per deployment.
 *
 * <p>{@code clockSkew} is SPEC-UA-006's own addition (10-failure-handling:
 * "Token clock skew: validate nbf/exp within a small fixed window";
 * 09-concurrency-and-idempotency: "Configured clock skew is bounded and
 * never enlarged to accept expired tokens") — capped at {@link #MAX_CLOCK_SKEW}
 * so a misconfiguration can never silently widen the acceptance window far
 * enough to matter; Spring's own {@code JwtTimestampValidator} default (60s)
 * is used unless overridden, just made explicit and bounded rather than implicit.
 *
 * <p>{@code jwksMaxStale} is SPEC-UA-032's own addition (10-failure-handling:
 * "JWKS endpoint unavailable | Use only keys within max-stale and matching
 * issuer"). No LLD section names a concrete numeric bound — same
 * "choose and document one" precedent {@code clockSkew} (above) and
 * SPEC-UA-019's own break-glass {@code MAX_TTL} both already established
 * — capped at {@link #MAX_JWKS_MAX_STALE} so a misconfiguration can never
 * leave the service trusting a genuinely stale/potentially-rotated-away
 * key set indefinitely.
 */
@ConfigurationProperties(prefix = "app.identity.oidc")
public record OidcIssuerProperties(
    List<String> allowedAlgorithms,
    List<String> allowedAudiences,
    Duration discoveryCacheTtl,
    Duration clockSkew,
    Duration jwksMaxStale
) {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(2);
    private static final Duration MAX_JWKS_MAX_STALE = Duration.ofHours(2);

    public OidcIssuerProperties {
        allowedAlgorithms = (allowedAlgorithms == null || allowedAlgorithms.isEmpty()) ? List.of("RS256") : List.copyOf(allowedAlgorithms);
        allowedAudiences = allowedAudiences == null ? List.of() : List.copyOf(allowedAudiences);
        discoveryCacheTtl = discoveryCacheTtl == null ? Duration.ofMinutes(15) : discoveryCacheTtl;
        clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
        if (clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new IllegalArgumentException("app.identity.oidc.clock-skew must be between 0 and " + MAX_CLOCK_SKEW + ", was " + clockSkew);
        }
        jwksMaxStale = jwksMaxStale == null ? Duration.ofMinutes(30) : jwksMaxStale;
        if (jwksMaxStale.isNegative() || jwksMaxStale.compareTo(MAX_JWKS_MAX_STALE) > 0) {
            throw new IllegalArgumentException("app.identity.oidc.jwks-max-stale must be between 0 and " + MAX_JWKS_MAX_STALE + ", was " + jwksMaxStale);
        }
    }
}
