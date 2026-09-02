package com.opsmind.attachment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Spring Security's {@code oauth2ResourceServer().jwt()} config requires a
 * {@code JwtDecoder} bean to exist to build the filter chain at all, even in a
 * {@code @WebMvcTest} slice that only ever authenticates requests via
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} (which injects a mock
 * authentication directly and never actually calls this decoder) — so a real signed
 * test-token mechanism (mirroring ticket-workflow-service's own TestJwtSupport) isn't
 * needed here, only a bean of the right type to satisfy that startup-time wiring.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException("real JWT decoding is never exercised in this test slice");
        };
    }
}
