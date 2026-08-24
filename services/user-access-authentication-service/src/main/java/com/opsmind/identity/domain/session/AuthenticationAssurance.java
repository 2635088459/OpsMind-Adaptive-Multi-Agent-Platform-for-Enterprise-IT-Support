package com.opsmind.identity.domain.session;

import java.time.Instant;
import java.util.List;

/** 01-domain-model §Value Objects. {@code acr}/{@code amr} mirror the OIDC claims of the same name; full assurance-level computation is SPEC-UA-016's job. */
public record AuthenticationAssurance(String acr, List<String> amr, Instant authTime) {

    public AuthenticationAssurance {
        if (acr == null || acr.isBlank()) {
            throw new IllegalArgumentException("acr must not be blank");
        }
        amr = List.copyOf(amr == null ? List.of() : amr);
        if (authTime == null) {
            throw new IllegalArgumentException("authTime must not be null");
        }
    }
}
