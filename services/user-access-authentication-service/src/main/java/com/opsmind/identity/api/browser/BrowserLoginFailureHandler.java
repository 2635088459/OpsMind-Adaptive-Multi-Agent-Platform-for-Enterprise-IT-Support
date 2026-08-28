package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.shared.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SPEC-UA-005 (04-use-cases §OIDC login: "Reject and audit mismatched
 * state/nonce/code"). Runs whenever Spring Security's own {@code
 * oauth2Login} rejects the callback — invalid/expired {@code state}, a
 * nonce mismatch on the ID token, or a failed code exchange — none of
 * which this class re-validates; it only records the denial and redirects.
 * 05-api-contracts: "security errors do not reveal token-validation
 * internals" — the audit reason is the exception's class name only, never
 * its full message (which can embed raw token/claim fragments); the full
 * exception is logged server-side for operators.
 */
@Component
public class BrowserLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(BrowserLoginFailureHandler.class);

    private final AuditPort auditPort;
    private final ClockPort clock;
    private final BrowserLoginProperties properties;

    public BrowserLoginFailureHandler(AuditPort auditPort, ClockPort clock, BrowserLoginProperties properties) {
        this.auditPort = auditPort;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws java.io.IOException {
        log.warn("browser OIDC login callback rejected", exception);
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId(properties.defaultTenantId()), IdentityAuditAction.USER_SESSION_STARTED,
            null, null, null, AuditOutcome.DENIED, exception.getClass().getSimpleName(),
            new CorrelationId(UUID.randomUUID().toString()), clock.now()
        ));
        response.sendRedirect(properties.failureRedirectUri());
    }
}
