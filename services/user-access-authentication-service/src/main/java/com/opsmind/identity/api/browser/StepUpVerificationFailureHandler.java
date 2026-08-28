package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.config.BrowserLoginProperties;
import com.opsmind.identity.config.StepUpVerificationProperties;
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
 * SPEC-UA-018's own mirror of {@link BrowserLoginFailureHandler}: runs
 * whenever Spring Security's own {@code oauth2Login} rejects the step-up
 * callback itself (invalid/expired state, a nonce mismatch on the ID token,
 * a failed code exchange) — before any real evidence ever reaches {@link
 * StepUpVerificationSuccessHandler}. There is no challenge id to audit
 * against yet at this point (the malformed/rejected state may not even
 * parse), so this records a domain-level denial fact rather than a
 * per-challenge one — mirroring how {@code BrowserLoginFailureHandler}
 * itself has no linked identity to audit against either at this stage.
 */
@Component
public class StepUpVerificationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(StepUpVerificationFailureHandler.class);

    private final AuditPort auditPort;
    private final ClockPort clock;
    private final BrowserLoginProperties tenantProperties;
    private final StepUpVerificationProperties properties;

    public StepUpVerificationFailureHandler(
        AuditPort auditPort, ClockPort clock, BrowserLoginProperties tenantProperties, StepUpVerificationProperties properties
    ) {
        this.auditPort = auditPort;
        this.clock = clock;
        this.tenantProperties = tenantProperties;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws java.io.IOException {
        log.warn("step-up re-authentication callback rejected", exception);
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), new TenantId(tenantProperties.defaultTenantId()), IdentityAuditAction.STEPUP_FAILED,
            null, null, null, AuditOutcome.DENIED, exception.getClass().getSimpleName(),
            new CorrelationId(UUID.randomUUID().toString()), clock.now()
        ));
        response.sendRedirect(properties.failureRedirectUri());
    }
}
