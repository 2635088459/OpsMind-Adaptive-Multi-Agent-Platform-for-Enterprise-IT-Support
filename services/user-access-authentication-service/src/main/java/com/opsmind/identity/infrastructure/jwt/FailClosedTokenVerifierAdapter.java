package com.opsmind.identity.infrastructure.jwt;

import com.opsmind.identity.application.port.out.TokenVerifierPort;
import org.springframework.stereotype.Component;

/**
 * SPEC-UA-001-scoped placeholder: always reports invalid (INV-UA-002 fail
 * closed). Real JWKS-backed programmatic verification is SPEC-UA-006's job
 * (JWT Validation JWKS Rotation) — note that baseline per-request issuer/
 * audience/signature/expiry validation is already real via {@code
 * config.SecurityConfig}'s standard OAuth2 resource server filter chain;
 * this port only covers supplementary off-request-thread verification.
 */
@Component
public class FailClosedTokenVerifierAdapter implements TokenVerifierPort {

    @Override
    public boolean isValid(String tokenIdHash) {
        return false;
    }
}
