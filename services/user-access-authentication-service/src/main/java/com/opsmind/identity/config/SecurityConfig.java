package com.opsmind.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.api.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * INV-UA-002 (deny by default): every request is authenticated unless
 * explicitly permitted, and every JWT is validated by Spring's standard
 * OAuth2 resource server support — issuer, audience, signature, and expiry
 * (11-security §Tokens and protocols) — once {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri} is configured (see
 * {@code application-local.yml}). This filter chain is the baseline only;
 * real OIDC discovery/JWKS rotation is SPEC-UA-004's/SPEC-UA-006's job, and
 * per-endpoint RBAC/ABAC beyond "authenticated" is SPEC-UA-011/012/014's.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, authException) -> writeError(
                    response, objectMapper, request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required."
                ))
                .accessDeniedHandler((request, response, accessDeniedException) -> writeError(
                    response, objectMapper, request, HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action."
                ))
            );

        return http.build();
    }

    private void writeError(
        HttpServletResponse response, ObjectMapper objectMapper, HttpServletRequest request, HttpStatus status, String code, String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String correlationId = request.getHeader(IdentityRequestContext.CORRELATION_ID_HEADER);
        ErrorResponse body = ErrorResponse.of(code, message, correlationId == null ? "" : correlationId, false);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
