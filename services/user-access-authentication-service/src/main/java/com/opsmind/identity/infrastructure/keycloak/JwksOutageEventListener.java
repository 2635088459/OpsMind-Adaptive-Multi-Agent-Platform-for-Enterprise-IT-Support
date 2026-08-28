package com.opsmind.identity.infrastructure.keycloak;

import com.nimbusds.jose.jwk.source.OutageTolerantJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.events.Event;
import com.nimbusds.jose.util.events.EventListener;
import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPEC-UA-032 (10-failure-handling: "JWKS endpoint unavailable | Use only
 * keys within max-stale and matching issuer; reject unknown kid |
 * Single-flight refresh/reconcile"). Fires exactly when Nimbus's own
 * {@link OutageTolerantJWKSetSource} — wired into {@code
 * SecurityConfig#jwtDecoder} — actually falls back to a previously-cached
 * key set because a live JWKS fetch failed; this is the only real signal
 * this codebase has for "we are currently degraded on JWKS," so it both
 * logs (never at ERROR — a bounded, expected degraded mode, not an
 * unhandled failure) and records the real {@link
 * IdentityMetricsPort#recordJwksDegradedFallback()} counter.
 */
public class JwksOutageEventListener implements EventListener<OutageTolerantJWKSetSource<SecurityContext>, SecurityContext> {

    private static final Logger log = LoggerFactory.getLogger(JwksOutageEventListener.class);

    private final IdentityMetricsPort identityMetricsPort;

    public JwksOutageEventListener(IdentityMetricsPort identityMetricsPort) {
        this.identityMetricsPort = identityMetricsPort;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void notify(Event<OutageTolerantJWKSetSource<SecurityContext>, SecurityContext> event) {
        if (event instanceof OutageTolerantJWKSetSource.OutageEvent outage) {
            log.warn(
                "JWKS endpoint fetch failed, serving cached keys within the configured max-stale window ({} ms remaining): {}",
                outage.getRemainingTime(), outage.getException().getMessage()
            );
            identityMetricsPort.recordJwksDegradedFallback();
        }
    }
}
