package dev.opsmind.ticketworkflow.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.platform.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(PortalCorsProperties.class)
public class SecurityConfiguration {

    /** SPEC-EP-013/016/017: domain 09/10's own frontend calls this service's public API directly from a real browser origin. Empty/deny by default — see PortalCorsProperties's own javadoc. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(PortalCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "If-None-Match", "Idempotency-Key", "X-Correlation-Id", "traceparent"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * SPEC-EP-014/020: a browser {@code EventSource} genuinely cannot set an
     * {@code Authorization} header, so {@code GET
     * /api/v1/tickets/{ticketId}/events} is the one route in this service
     * that also accepts a bearer token as a {@code ?token=} query
     * parameter. Scoped to exactly that one path via {@code
     * securityMatcher} below, never a blanket allowance on the main chain
     * -- a bearer token sitting in a query string is a real log/Referer
     * leakage risk this service does not want on every other route.
     * {@code @Order(1)} makes this chain's own, narrower {@code
     * securityMatcher} get first refusal; every other request falls
     * through to {@code securityFilterChain} below unaffected.
     *
     * Real bug found live: {@link DefaultBearerTokenResolver}'s own {@code
     * setAllowUriQueryParameter(true)} only ever reads the OAuth2-spec
     * query parameter name, {@code access_token} (a private static final
     * constant -- there is no setter to rename it) -- but
     * useTicketStatusStream.ts's own real, already-shipped contract sends
     * {@code ?token=}, not {@code ?access_token=}. {@link
     * QueryParameterBearerTokenResolver} below is the smallest fix that
     * matches the contract the frontend already committed to, rather than
     * quietly renaming the frontend's own query parameter to match a
     * library default it was never written against.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain ticketEventsSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper, CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .securityMatcher("/api/v1/tickets/*/events")
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(new QueryParameterBearerTokenResolver())
                .jwt(Customizer.withDefaults()))
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

    @Bean
    @Order(2)
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

    /**
     * Resolves the real {@code Authorization} header first (unchanged
     * behavior for any non-browser caller, e.g. this service's own IT
     * suite), falling back to the {@code token} query parameter only when
     * no header is present -- the exact name/shape
     * useTicketStatusStream.ts's own {@code EventSource} call already
     * sends (see this class's own javadoc for why {@link
     * DefaultBearerTokenResolver} itself cannot be configured to accept
     * that name).
     */
    private static final class QueryParameterBearerTokenResolver implements BearerTokenResolver {
        private final BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

        @Override
        public String resolve(HttpServletRequest request) {
            String headerToken = headerResolver.resolve(request);
            return headerToken != null ? headerToken : request.getParameter("token");
        }
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
