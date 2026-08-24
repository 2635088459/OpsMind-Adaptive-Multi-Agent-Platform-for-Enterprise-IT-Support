package com.opsmind.identity.infrastructure.keycloak;

import com.opsmind.identity.application.port.out.OidcProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SPEC-UA-001-scoped placeholder: always reports unavailable so a caller
 * depending on {@link OidcProviderPort} fails closed (INV-UA-002) rather
 * than silently no-op-succeeding. Real Keycloak discovery/PKCE/end-session
 * integration is SPEC-UA-004's and SPEC-UA-005's job.
 */
@Component
public class UnavailableOidcProviderAdapter implements OidcProviderPort {

    private static final Logger log = LoggerFactory.getLogger(UnavailableOidcProviderAdapter.class);

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void requestEndSession(String idpSessionIdHash) {
        log.warn("OidcProviderPort not yet integrated (see SPEC-UA-004/SPEC-UA-005); skipping IdP end-session notification");
    }
}
