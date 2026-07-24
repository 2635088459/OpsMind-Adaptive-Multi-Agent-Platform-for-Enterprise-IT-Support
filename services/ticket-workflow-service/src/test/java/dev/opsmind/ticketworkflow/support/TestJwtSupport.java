package dev.opsmind.ticketworkflow.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * Mints RS256 JWTs signed with a fixed in-process test key pair, so
 * integration tests can exercise the real Spring Security OAuth2 resource
 * server filter chain end-to-end without a running Keycloak. Pair with
 * {@link TestSecurityConfiguration}, which registers a {@code JwtDecoder}
 * bean built from the matching public key.
 */
public final class TestJwtSupport {

    public static final KeyPair KEY_PAIR = generateKeyPair();
    public static final String ISSUER = "https://test-issuer.opsmind.invalid/realms/test";

    private TestJwtSupport() {
    }

    public static String mintToken(String subject, String clientId, Set<String> scopes) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .claim("azp", clientId)
                .claim("scope", String.join(" ", scopes))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();

            SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                claims
            );
            signedJwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
            return signedJwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to mint test jwt", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
