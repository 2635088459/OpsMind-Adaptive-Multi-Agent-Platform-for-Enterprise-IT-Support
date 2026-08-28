package com.opsmind.identity.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.opsmind.identity.infrastructure.keycloak.OidcDiscoveryClient;
import com.opsmind.identity.support.InMemoryIdentityMetricsPort;
import com.opsmind.identity.support.StubHttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-UA-004 (11-security §Tokens and protocols: "Reject alg=none,
 * algorithm confusion, and arbitrary jku/x5u"). Exercises {@link
 * SecurityConfig#jwtDecoder} end-to-end over real HTTP against a real RSA
 * keypair — the only way to genuinely prove the algorithm allow-list and
 * audience validator reject what they claim to, rather than trusting
 * Nimbus's defaults implicitly.
 */
@Tag("unit")
class SecurityConfigJwtDecoderTest {

    private static final Instant NOW = Instant.now();

    @Test
    void acceptsATokenSignedWithTheAllowedAlgorithmAndRejectsAnUnknownIssuerOrAlgorithmConfusion() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        try (StubHttpServer server = StubHttpServer.create()) {
            String issuer = server.baseUrl();
            server.route("/.well-known/openid-configuration", () -> discoveryJson(issuer, server.baseUrl() + "/certs"));
            server.route("/certs", () -> new JWKSet(rsaKey.toPublicJWK()).toString());
            server.start();

            SecurityConfig config = new SecurityConfig();
            JwtDecoder decoder = config.jwtDecoder(
                new OidcDiscoveryClient(), new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), null, null),
                new InMemoryIdentityMetricsPort(), issuer
            );

            String validToken = signRs256(rsaKey, issuer, "sub-1", null);
            Jwt decoded = decoder.decode(validToken);
            assertThat(decoded.getSubject()).isEqualTo("sub-1");

            String wrongIssuerToken = signRs256(rsaKey, "https://not-the-configured-issuer.invalid", "sub-1", null);
            assertThatThrownBy(() -> decoder.decode(wrongIssuerToken)).isInstanceOf(JwtException.class);

            String algorithmConfusionToken = signHs256UsingTheRsaModulusAsAGuessedSecret(rsaKey, issuer, "sub-1");
            assertThatThrownBy(() -> decoder.decode(algorithmConfusionToken)).isInstanceOf(JwtException.class);

            String unsignedToken = new PlainJWT(new JWTClaimsSet.Builder().issuer(issuer).subject("sub-1")
                .expirationTime(Date.from(NOW.plusSeconds(300))).build()).serialize();
            assertThatThrownBy(() -> decoder.decode(unsignedToken)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsATokenWhoseAudienceIsNotInTheConfiguredAllowList() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("kid-2").generate();
        try (StubHttpServer server = StubHttpServer.create()) {
            String issuer = server.baseUrl();
            server.route("/.well-known/openid-configuration", () -> discoveryJson(issuer, server.baseUrl() + "/certs"));
            server.route("/certs", () -> new JWKSet(rsaKey.toPublicJWK()).toString());
            server.start();

            SecurityConfig config = new SecurityConfig();
            JwtDecoder decoder = config.jwtDecoder(
                new OidcDiscoveryClient(),
                new OidcIssuerProperties(List.of("RS256"), List.of("identity-api"), Duration.ofMinutes(15), null, null),
                new InMemoryIdentityMetricsPort(), issuer
            );

            String wrongAudience = signRs256(rsaKey, issuer, "sub-1", "some-other-audience");
            assertThatThrownBy(() -> decoder.decode(wrongAudience)).isInstanceOf(JwtException.class);

            String rightAudience = signRs256(rsaKey, issuer, "sub-1", "identity-api");
            assertThat(decoder.decode(rightAudience).getSubject()).isEqualTo("sub-1");
        }
    }

    /** SPEC-UA-006 (10-failure-handling: "Token clock skew: validate nbf/exp within a small fixed window"). */
    @Test
    void clockSkewAcceptsATokenJustExpiredWithinTheConfiguredWindowAndRejectsOneFurtherOut() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("kid-skew").generate();
        try (StubHttpServer server = StubHttpServer.create()) {
            String issuer = server.baseUrl();
            server.route("/.well-known/openid-configuration", () -> discoveryJson(issuer, server.baseUrl() + "/certs"));
            server.route("/certs", () -> new JWKSet(rsaKey.toPublicJWK()).toString());
            server.start();

            SecurityConfig config = new SecurityConfig();
            JwtDecoder decoder = config.jwtDecoder(
                new OidcDiscoveryClient(), new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), Duration.ofSeconds(30), null),
                new InMemoryIdentityMetricsPort(), issuer
            );

            String expiredWithinSkew = signRs256Expiring(rsaKey, issuer, NOW.minusSeconds(20));
            assertThat(decoder.decode(expiredWithinSkew).getSubject()).isEqualTo("sub-1");

            String expiredBeyondSkew = signRs256Expiring(rsaKey, issuer, NOW.minusSeconds(90));
            assertThatThrownBy(() -> decoder.decode(expiredBeyondSkew)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void clockSkewIsRejectedByOidcIssuerPropertiesWhenConfiguredBeyondTheBoundedMaximum() {
        assertThatThrownBy(() -> new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), Duration.ofMinutes(5), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** SPEC-UA-032: same bounded-cap discipline as {@link #clockSkewIsRejectedByOidcIssuerPropertiesWhenConfiguredBeyondTheBoundedMaximum}, for {@code jwksMaxStale}. */
    @Test
    void jwksMaxStaleIsRejectedByOidcIssuerPropertiesWhenConfiguredBeyondTheBoundedMaximum() {
        assertThatThrownBy(() -> new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), null, Duration.ofHours(3)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * SPEC-UA-006 (10-failure-handling: "JWKS endpoint unavailable | Use
     * only keys within max-stale and matching issuer; reject unknown kid |
     * Single-flight refresh/reconcile"). Nimbus's own composed {@link
     * com.nimbusds.jose.jwk.source.JWKSourceBuilder} (SPEC-UA-032) provides
     * the refresh-on-unknown-kid mechanism this test proves — not
     * reimplemented here, just verified against this service's own {@link
     * SecurityConfig#jwtDecoder}. SPEC-UA-032 additionally enables the
     * explicit {@code rateLimited(...)} layer this test's own second half
     * exercises: "one rate-limited refresh" (09-concurrency-and-idempotency)
     * means a SECOND unknown-kid lookup within the rate-limit window of the
     * first real refresh never reaches the network at all.
     */
    @Test
    void unknownKidTriggersARefreshThatFindsARotatedInKeyButStaysRejectedIfStillNotFound() throws Exception {
        RSAKey keyA = new RSAKeyGenerator(2048).keyID("kid-a").generate();
        RSAKey keyB = new RSAKeyGenerator(2048).keyID("kid-b").generate();
        AtomicInteger jwksRequests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<JWKSet> currentJwks = new java.util.concurrent.atomic.AtomicReference<>(new JWKSet(keyA.toPublicJWK()));
        try (StubHttpServer server = StubHttpServer.create()) {
            String issuer = server.baseUrl();
            server.route("/.well-known/openid-configuration", () -> discoveryJson(issuer, server.baseUrl() + "/certs"));
            server.route("/certs", () -> {
                jwksRequests.incrementAndGet();
                return currentJwks.get().toString();
            });
            server.start();

            SecurityConfig config = new SecurityConfig();
            JwtDecoder decoder = config.jwtDecoder(
                new OidcDiscoveryClient(), new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), null, null),
                new InMemoryIdentityMetricsPort(), issuer
            );

            String tokenA = signRs256(keyA, issuer, "sub-1", null);
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("sub-1");
            int requestsAfterFirstDecode = jwksRequests.get();

            // Rotation: keyB is added to the published JWKS after the decoder already cached {keyA}.
            currentJwks.set(new JWKSet(List.of(keyA.toPublicJWK(), keyB.toPublicJWK())));
            String tokenB = signRs256(keyB, issuer, "sub-1", null);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("sub-1");
            assertThat(jwksRequests.get()).isGreaterThan(requestsAfterFirstDecode);

            // A kid that has never been published anywhere, probed immediately after tokenB's own real
            // refresh (still well within the rate-limit window): the rate limiter blocks a second network
            // fetch this soon, so the decoder fails closed against the still-cached set with zero new requests.
            RSAKey neverPublished = new RSAKeyGenerator(2048).keyID("kid-never-published").generate();
            String unknownToken = signRs256(neverPublished, issuer, "sub-1", null);
            int requestsBeforeUnknown = jwksRequests.get();
            assertThatThrownBy(() -> decoder.decode(unknownToken)).isInstanceOf(JwtException.class);
            assertThat(jwksRequests.get()).isEqualTo(requestsBeforeUnknown);
        }
    }

    /**
     * SPEC-UA-032 (10-failure-handling: "JWKS endpoint unavailable | Use
     * only keys within max-stale and matching issuer; reject unknown kid").
     * The JWKS endpoint starts returning unparseable content (a real
     * transport-level failure would work identically, but this is fast and
     * deterministic) — an already-cached, already-known key keeps
     * validating through the outage (the real value of this spec: an
     * active low-risk session is not disrupted by a live JWKS blip), while
     * a genuinely unknown kid is still correctly rejected, not silently
     * trusted, even mid-outage. {@link JwksOutageEventListener} (via
     * {@link IdentityMetricsPort#recordJwksDegradedFallback()}) is the
     * only real signal this codebase has for "degraded JWKS mode was
     * actually entered" — asserted directly, not inferred.
     */
    @Test
    void jwksOutageServesTheLastKnownGoodKeySetForAlreadyKnownKidsButStillRejectsAnUnknownOne() throws Exception {
        RSAKey keyA = new RSAKeyGenerator(2048).keyID("kid-outage-a").generate();
        java.util.concurrent.atomic.AtomicBoolean jwksEndpointIsDown = new java.util.concurrent.atomic.AtomicBoolean(false);
        try (StubHttpServer server = StubHttpServer.create()) {
            String issuer = server.baseUrl();
            server.route("/.well-known/openid-configuration", () -> discoveryJson(issuer, server.baseUrl() + "/certs"));
            server.route("/certs", () -> jwksEndpointIsDown.get() ? "{not valid jwks json" : new JWKSet(keyA.toPublicJWK()).toString());
            server.start();

            SecurityConfig config = new SecurityConfig();
            InMemoryIdentityMetricsPort metrics = new InMemoryIdentityMetricsPort();
            JwtDecoder decoder = config.jwtDecoder(
                new OidcDiscoveryClient(), new OidcIssuerProperties(List.of("RS256"), List.of(), Duration.ofMinutes(15), null, Duration.ofMinutes(5)),
                metrics, issuer
            );

            String tokenA = signRs256(keyA, issuer, "sub-1", null);
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("sub-1");
            assertThat(metrics.jwksDegradedFallbackCount()).isZero();

            jwksEndpointIsDown.set(true);
            RSAKey neverPublished = new RSAKeyGenerator(2048).keyID("kid-outage-never-published").generate();
            String unknownToken = signRs256(neverPublished, issuer, "sub-1", null);
            assertThatThrownBy(() -> decoder.decode(unknownToken)).isInstanceOf(JwtException.class);
            assertThat(metrics.jwksDegradedFallbackCount()).isGreaterThan(0);

            // The already-known key from before the outage still validates via the cached fallback.
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("sub-1");
        }
    }

    private static String signRs256Expiring(RSAKey rsaKey, String issuer, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(issuer).subject("sub-1").issueTime(Date.from(NOW.minusSeconds(300))).expirationTime(Date.from(expiresAt)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    private static String discoveryJson(String issuer, String jwksUri) {
        return """
            {"issuer": "%s", "jwks_uri": "%s"}
            """.formatted(issuer, jwksUri);
    }

    private static String signRs256(RSAKey rsaKey, String issuer, String subject, String audience) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(issuer).subject(subject).issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(300)));
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    /** Represents the classic RS256->HS256 "algorithm confusion" attack: signs with HS256 using bytes derived from the RSA key as a guessed HMAC secret. */
    private static String signHs256UsingTheRsaModulusAsAGuessedSecret(RSAKey rsaKey, String issuer, String subject) throws Exception {
        byte[] guessedSecret = rsaKey.toRSAPublicKey().getModulus().toByteArray();
        byte[] secret = guessedSecret.length >= 32 ? guessedSecret : java.util.Arrays.copyOf(guessedSecret, 32);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(issuer).subject(subject).issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(300))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }
}
