package com.opsmind.attachment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.attachment.platform.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * Real JWT resource-server authentication against the shared Keycloak realm, mirroring
 * ticket-workflow-service's own SecurityConfiguration exactly — this is genuinely
 * security-sensitive (arbitrary file upload/download), unlike the Python services'
 * own caller-asserted-header placeholders elsewhere in this platform. Any
 * authenticated employee/agent may upload or read an attachment today — there is no
 * per-attachment ownership check yet (an honest, carried-forward gap, the same class
 * as the platform's other known fine-grained-authorization gaps, e.g.
 * ApprovalController's own).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AttachmentCorsProperties.class)
public class SecurityConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AttachmentCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "traceparent"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
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
        String traceId = MDC.get("traceId");
        String correlationId = request.getHeader("X-Correlation-Id");
        ErrorResponse body = ErrorResponse.of(code, message, traceId == null ? "" : traceId, correlationId == null ? "" : correlationId);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
