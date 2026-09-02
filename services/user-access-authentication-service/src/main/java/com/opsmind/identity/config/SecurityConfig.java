package com.opsmind.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.api.error.ErrorResponse;
import com.opsmind.identity.api.browser.BrowserAuthenticationFailureDispatcher;
import com.opsmind.identity.api.browser.BrowserAuthenticationSuccessDispatcher;
import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.infrastructure.keycloak.JwksOutageEventListener;
import com.opsmind.identity.infrastructure.keycloak.OidcDiscoveryClient;
import com.opsmind.identity.infrastructure.keycloak.OidcDiscoveryDocument;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * INV-UA-002 (deny by default): every request is authenticated unless
 * explicitly permitted, and every JWT is validated — issuer, audience,
 * signature, expiry, and a restricted signature-algorithm allow-list
 * (11-security §Tokens and protocols: "Reject alg=none, algorithm
 * confusion, and arbitrary jku/x5u") — once {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri} is configured (see
 * {@code application-local.yml}). {@link #jwtDecoder} (SPEC-UA-004) is an
 * explicit replacement for Spring Boot's own autoconfigured decoder — same
 * default behavior (a blocking discovery fetch at startup against the
 * configured issuer), but built through this service's own {@link
 * OidcDiscoveryClient} so the jws-algorithm allow-list and audience
 * restriction are real and inspectable rather than implicit. {@code
 * @ConditionalOnMissingBean} lets a test still override it the same way it
 * would override the autoconfigured one. JWKS rotation itself (a new key
 * appearing, an unknown {@code kid} triggering exactly one rate-limited
 * refresh before failing closed) is Nimbus's own caching/rate-limiting
 * behavior, not reimplemented — SPEC-UA-006's own addition is the explicit,
 * bounded clock-skew tolerance (10-failure-handling: "Token clock skew:
 * validate nbf/exp within a small fixed window") built from {@link
 * JwtTimestampValidator} + {@link JwtIssuerValidator} directly instead of
 * {@code JwtValidators.createDefaultWithIssuer}'s implicit default skew.
 * Per-endpoint RBAC/ABAC beyond "authenticated" is SPEC-UA-011/012/014's.
 *
 * <p>SPEC-UA-032 (10-failure-handling: "JWKS endpoint unavailable | Use
 * only keys within max-stale and matching issuer; reject unknown kid |
 * Single-flight refresh/reconcile") replaces the plain {@code
 * NimbusJwtDecoder.withJwkSetUri(...)} builder this bean used through
 * SPEC-UA-006 with the lower-level {@code NimbusJwtDecoder(JWTProcessor)}
 * constructor, so a custom, outage-tolerant {@link JWKSource} can be
 * plugged in — the public {@code withJwkSetUri} builder exposes no seam
 * for this. {@link JWKSourceBuilder#outageTolerant(long,
 * com.nimbusds.jose.util.events.EventListener)} is Nimbus's own
 * purpose-built mechanism for exactly this behavior; it only ever serves a
 * previously-cached key set when the underlying fetch genuinely throws,
 * and only within {@link OidcIssuerProperties#jwksMaxStale} — an unknown
 * {@code kid} in an otherwise-reachable JWKS still triggers a real refresh
 * attempt first (unaffected), and once max-stale is exceeded the outage
 * source itself rethrows, failing closed exactly as before this spec.
 * {@link JwksOutageEventListener} is the one place this fallback path is
 * actually observed — see its own javadoc.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({OidcIssuerProperties.class, BrowserLoginProperties.class, KeycloakAdminProperties.class, StepUpVerificationProperties.class, BrowserCorsProperties.class})
public class SecurityConfig {

    /**
     * Backs {@code http.cors(...)} on both filter chains below. Origins come
     * only from {@link BrowserCorsProperties} (empty/deny by default,
     * INV-UA-002) — never a wildcard, since {@code allowCredentials(true)} is
     * required for the session cookie {@link
     * com.opsmind.identity.api.browser.BrowserSessionTokenController} relies
     * on, and the CORS spec itself forbids combining a wildcard origin with
     * credentials.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(BrowserCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", IdentityRequestContext.CORRELATION_ID_HEADER));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(
        OidcDiscoveryClient discoveryClient, OidcIssuerProperties properties, IdentityMetricsPort identityMetricsPort,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri
    ) {
        OidcDiscoveryDocument discovery = discoveryClient.fetch(issuerUri);
        List<SignatureAlgorithm> allowedAlgorithms = properties.allowedAlgorithms().stream().map(SignatureAlgorithm::from).toList();

        JWKSource<SecurityContext> jwkSource;
        try {
            jwkSource = JWKSourceBuilder.<SecurityContext>create(java.net.URI.create(discovery.jwksUri()).toURL())
                .cache(JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE, JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT)
                .rateLimited(JWKSourceBuilder.DEFAULT_RATE_LIMIT_MIN_INTERVAL)
                .retrying(true)
                .outageTolerant(properties.jwksMaxStale().toMillis(), new JwksOutageEventListener(identityMetricsPort))
                .build();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("issuer " + issuerUri + " published a malformed jwks_uri: " + discovery.jwksUri(), e);
        }

        Set<JWSAlgorithm> jwsAlgorithms = allowedAlgorithms.stream().map(alg -> JWSAlgorithm.parse(alg.getName())).collect(java.util.stream.Collectors.toSet());
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(jwsAlgorithms, jwkSource));
        // Claims validation (issuer/audience/timestamp) happens entirely through the OAuth2TokenValidator
        // set below, same as the previous withJwkSetUri(...)-built decoder — this processor-level verifier
        // deliberately stays a no-op so there is exactly one place claims are actually checked.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);

        List<OAuth2TokenValidator<Jwt>> validators = new java.util.ArrayList<>(List.of(
            new JwtTimestampValidator(properties.clockSkew()), new JwtIssuerValidator(issuerUri)
        ));
        if (!properties.allowedAudiences().isEmpty()) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.stream().anyMatch(properties.allowedAudiences()::contains)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * SPEC-UA-005: the real browser Authorization Code + PKCE login/callback
     * (04-use-cases §OIDC login). {@code @Order(1)} so this session-based
     * chain claims {@code /oauth2/**}/{@code /login/**} before {@link
     * #securityFilterChain}'s stateless one matches everything else — the
     * authorization-request round trip (state/PKCE verifier/nonce) needs
     * somewhere to live between the redirect and the callback, which a
     * fully stateless chain cannot provide. CSRF stays enabled (Spring's
     * default) per 11-security "Browser sessions use ... CSRF protection";
     * both endpoints are GET, so it never applies to them anyway. PKCE is
     * forced on via {@link OAuth2AuthorizationRequestCustomizers#withPkce()}
     * regardless of whether the configured client is confidential, per
     * 11-security's own literal wording ("Use Authorization Code + PKCE").
     *
     * <p>SPEC-UA-018: the exact same chain also carries the {@code
     * opsmind-stepup} registration's own real step-up re-authentication
     * round trip — {@link StepUpAuthorizationRequestResolver} (not the
     * plain PKCE-only resolver SPEC-UA-005 used alone) and the dispatching
     * success/failure handlers route each registration to its own real
     * flow without a second filter chain.
     *
     * <p>Also carries {@link
     * com.opsmind.identity.api.browser.BrowserSessionTokenController}'s own
     * {@code /api/v1/session/browser-token} — the one endpoint that needs to
     * read back the very session {@code oauth2Login} establishes here,
     * which {@link #securityFilterChain} (fully stateless) never creates or
     * consults. Unlike {@code /oauth2/**}/{@code /login/**} (framework
     * entry points, never gated), this path requires a real authenticated
     * principal — {@code permitAll()} would let an anonymous cross-origin
     * caller probe it for a 401-vs-200 timing/shape signal for no reason.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain browserLoginFilterChain(
        HttpSecurity http, ClientRegistrationRepository clientRegistrationRepository,
        BrowserAuthenticationSuccessDispatcher successHandler, BrowserAuthenticationFailureDispatcher failureHandler,
        CorsConfigurationSource corsConfigurationSource, ObjectMapper objectMapper
    ) throws Exception {
        http
            // PathPatternRequestMatcher, not the String-varargs overload: the latter builds an
            // MvcRequestMatcher when Spring MVC is on the classpath, which needs a real web
            // ApplicationContext's HandlerMappingIntrospector bean — unavailable (and unneeded)
            // in webEnvironment=NONE test contexts such as IdentityPersistenceIT.
            .securityMatcher(new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/oauth2/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/login/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/api/v1/session/browser-token")
            ))
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/oauth2/**", "/login/**").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(authorization -> authorization
                    .authorizationRequestResolver(new StepUpAuthorizationRequestResolver(clientRegistrationRepository, "/oauth2/authorization")))
                .successHandler(successHandler)
                .failureHandler(failureHandler)
            )
            // Real bug found live (verified against a real running Keycloak/browser-token
            // round trip): without this, oauth2Login()'s own default entry point for THIS
            // chain is a 302 to /login (Spring's auto-generated provider-picker HTML) —
            // fine for /oauth2/**+/login/** (permitAll, never triggers an entry point at
            // all) but wrong for an unauthenticated fetch() to /api/v1/session/browser-token:
            // a browser fetch follows that redirect silently and hands the SPA a 200 HTML
            // page instead of a 401 it can branch on. Same plain-JSON shape as
            // #securityFilterChain's own entry point below, not a redirect.
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, authException) -> writeError(
                    response, objectMapper, request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required."
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
