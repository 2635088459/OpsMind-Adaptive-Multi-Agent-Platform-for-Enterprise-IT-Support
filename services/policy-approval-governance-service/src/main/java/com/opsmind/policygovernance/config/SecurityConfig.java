package com.opsmind.policygovernance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.policygovernance.platform.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
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
 * Every governance API requires an authenticated actor (api-contract §API
 * Impact). Fine-grained per-endpoint authorization (approver role,
 * reviewer/publisher separation of duties) is enforced in the application
 * layer ({@code IdentityAuthorizationPort}, {@code PolicyAdminService}) —
 * see those types' javadoc for which future spec owns the full permission
 * model; this filter chain only establishes the authentication baseline.
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
        HttpServletResponse response,
        ObjectMapper objectMapper,
        HttpServletRequest request,
        HttpStatus status,
        String code,
        String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String traceId = MDC.get("traceId");
        String correlationId = request.getHeader("X-Correlation-Id");
        ErrorResponse body = ErrorResponse.of(
            code, message, traceId == null ? "" : traceId, correlationId == null ? "" : correlationId
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
