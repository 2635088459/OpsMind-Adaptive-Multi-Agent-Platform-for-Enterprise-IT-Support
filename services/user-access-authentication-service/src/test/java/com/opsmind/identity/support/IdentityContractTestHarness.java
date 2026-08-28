package com.opsmind.identity.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

/**
 * SPEC-UA-035 (14-testing-strategy: "SPEC-UA-035 supplies the cross-domain
 * harness"). Every one of SPEC-UA-020 through SPEC-UA-027's own real
 * HTTP/contract IT tests (8 classes) had independently duplicated the exact
 * same setup: generate an RSA keypair, start a JDK-only {@link
 * StubHttpServer} BEFORE Spring context refresh serving a real discovery
 * document + JWKS, register it as {@code issuer-uri} so the REAL {@code
 * SecurityConfig#jwtDecoder} bean validates real signed tokens over real
 * HTTP, and expose {@code port}/{@code restTemplate}/{@code baseUrl(...)}/
 * {@code bearer(...)} helpers. This class is that shared setup, extracted
 * once and adopted by all 8 — the literal "cross-domain harness" this spec
 * supplies, not a new, unused utility class sitting beside the still-duplicated
 * originals.
 *
 * <p>Deliberately does NOT also generalize {@code signedJwt(...)}/{@code
 * signedWorkloadJwt(...)} — each subclass's own claim set (plain human
 * login vs. client-credentials-shaped workload tokens with {@code aud}/
 * {@code scope}/{@code jti}) varies enough across the 8 original tests that
 * folding it in here would either lose real per-test intent or force an
 * over-generic signature; those helpers stay local to each subclass,
 * mirroring how {@link PostgresContainerSupport} itself only ever shared
 * the one genuinely identical piece (the container), not every test's own
 * fixture-building code.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)} is required, not decorative:
 * every subclass now inherits the exact same {@code registerIssuer}
 * method (same {@link java.lang.reflect.Method} object), so Spring's test
 * context cache would otherwise treat them as sharing one cacheable
 * context — meaning only the FIRST subclass to run would actually get its
 * own {@code keycloakStub} wired in, and every later one would silently
 * reuse that first context's already-closed stub server ({@link
 * #registerIssuer} never re-firing for them). This was caught only by
 * running all 8 adopting classes together, never by any single class run
 * in isolation. Before this harness existed, each class declared its own
 * distinct {@code registerIssuer} method, which happened to give every
 * class a fresh context "for free" without ever needing this annotation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IdentityContractTestHarness implements PostgresContainerSupport {

    protected static final RSAKey RSA_KEY = generateKey();
    protected static StubHttpServer keycloakStub;

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("identity-contract-harness-kid").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void registerIssuer(DynamicPropertyRegistry registry) {
        keycloakStub = StubHttpServer.create();
        String issuer = keycloakStub.baseUrl();
        keycloakStub.route("/.well-known/openid-configuration", () -> """
            {"issuer": "%s", "jwks_uri": "%s/certs"}
            """.formatted(issuer, issuer));
        keycloakStub.route("/certs", () -> new JWKSet(RSA_KEY.toPublicJWK()).toString());
        keycloakStub.start();
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    }

    @AfterAll
    static void stopStub() {
        if (keycloakStub != null) {
            keycloakStub.close();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    protected HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.add("X-Correlation-Id", UUID.randomUUID().toString());
        return headers;
    }
}
