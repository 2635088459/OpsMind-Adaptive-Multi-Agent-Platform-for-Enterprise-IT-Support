package com.opsmind.identity.api.internal;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.dto.IntrospectPrincipalContextRequest;
import com.opsmind.identity.application.dto.PrincipalContextView;
import com.opsmind.identity.application.port.in.IntrospectPrincipalUseCase;
import com.opsmind.identity.application.query.IntrospectPrincipalContextQuery;
import com.opsmind.identity.config.BrowserLoginProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 05-api-contracts {@code POST /tokens/introspect-context} ("trusted
 * workload | Token is never logged; returns normalized principal,
 * assurance, session status") — SPEC-UA-007's own primary deliverable.
 * Introspects only the caller's own already-verified bearer token (via
 * {@link IdentityRequestContext#verifiedJwt}) — this endpoint never
 * accepts a raw token as input, so there is never a raw token to log in
 * the first place. Pure query, no audit/outbox side effect (nothing state-
 * changing happens here), so unlike a command endpoint it needs neither a
 * correlation id nor an idempotency key (04-use-cases: "Every <em>command</em>
 * requires Idempotency-Key and X-Correlation-Id").
 */
@RestController
public class TokenIntrospectionController {

    private final IntrospectPrincipalUseCase introspectPrincipalUseCase;
    private final BrowserLoginProperties browserLoginProperties;

    public TokenIntrospectionController(IntrospectPrincipalUseCase introspectPrincipalUseCase, BrowserLoginProperties browserLoginProperties) {
        this.introspectPrincipalUseCase = introspectPrincipalUseCase;
        this.browserLoginProperties = browserLoginProperties;
    }

    @PostMapping("/internal/identity/v1/tokens/introspect-context")
    public ResponseEntity<PrincipalContextView> introspect(
        @RequestBody(required = false) IntrospectPrincipalContextRequest request, Authentication authentication
    ) {
        Jwt jwt = IdentityRequestContext.verifiedJwt(authentication);
        IdentityRequestContext.VerifiedIssuerAndSubject verified = IdentityRequestContext.verifiedIssuerAndSubject(authentication);
        String userSessionId = request == null ? null : request.userSessionId();

        IntrospectPrincipalContextQuery query = new IntrospectPrincipalContextQuery(
            browserLoginProperties.defaultTenantId(), verified.issuer(), verified.subject(),
            jwt.getClaimAsString("acr"), jwt.getClaimAsStringList("amr"), jwt.getClaimAsInstant("auth_time"), userSessionId
        );
        return ResponseEntity.ok(introspectPrincipalUseCase.introspect(query));
    }
}
