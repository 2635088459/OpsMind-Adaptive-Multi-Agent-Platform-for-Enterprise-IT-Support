package dev.opsmind.ticketworkflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;

/**
 * Registers a {@code JwtDecoder} bean built from {@link TestJwtSupport}'s
 * fixed key pair, so tests can hit real HTTP endpoints with real signed JWTs
 * instead of a live Keycloak. Spring Boot's auto-configured resource-server
 * JwtDecoder backs off once this bean is present.
 */
@TestConfiguration
public class TestSecurityConfiguration {

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) TestJwtSupport.KEY_PAIR.getPublic()).build();
    }
}
