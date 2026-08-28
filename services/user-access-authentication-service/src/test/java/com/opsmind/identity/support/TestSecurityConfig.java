package com.opsmind.identity.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Satisfies {@code SecurityConfig}'s {@code JwtDecoder} dependency for
 * persistence-layer integration tests that never exercise an HTTP endpoint
 * (so no token is ever actually decoded) — without this, Spring Boot's real
 * OAuth2 resource server autoconfiguration tries to fetch OIDC discovery
 * metadata from the fake test issuer over the network at context-startup
 * time and fails the whole context.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException("no HTTP endpoint is exercised by this test");
        };
    }
}
