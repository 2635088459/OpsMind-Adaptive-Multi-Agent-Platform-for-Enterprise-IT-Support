package com.opsmind.identity.api;

import com.opsmind.identity.api.error.RequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Extracts what every internal/admin identity API needs beyond its own
 * body: the authenticated actor and a correlation id (04-use-cases: "Every
 * command requires Idempotency-Key and X-Correlation-Id"). {@link
 * #verifiedIssuerAndSubject} is what makes identity-linking/session-start
 * unspoofable — {@code issuer}/{@code subject} come only from the caller's
 * own verified {@link Jwt}, never request-body input (02-business-invariants
 * #7: "Roles, tenant, subject ... supplied through client headers are
 * untrusted").
 */
public final class IdentityRequestContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private IdentityRequestContext() {
    }

    public static String actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new RequestValidationException("an authenticated actor is required");
        }
        return authentication.getName();
    }

    public static VerifiedIssuerAndSubject verifiedIssuerAndSubject(Authentication authentication) {
        Jwt jwt = verifiedJwt(authentication);
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new RequestValidationException("the verified JWT is missing iss or sub");
        }
        return new VerifiedIssuerAndSubject(issuer, subject);
    }

    /** The raw verified {@link Jwt} itself — for callers (SPEC-UA-007's introspection endpoint) that need claims beyond issuer/subject, e.g. {@code acr}/{@code amr}/{@code auth_time}. */
    public static Jwt verifiedJwt(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new RequestValidationException("a verified JWT principal is required");
        }
        return jwtAuth.getToken();
    }

    public static String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            throw new RequestValidationException(CORRELATION_ID_HEADER + " header is required");
        }
        return correlationId;
    }

    public record VerifiedIssuerAndSubject(String issuer, String subject) {
    }
}
