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

    /**
     * SPEC-UA-016's own exact-acr-match / must-contain-amr comparison,
     * extracted here so SPEC-UA-019's own break-glass strong-authentication
     * check (and any future caller) reuses the identical rule rather than
     * re-deriving it: a {@code null} {@code requiredAcr} means no level
     * requirement; {@code null}/empty {@code requiredMethods} means no
     * method requirement. Deliberately no ACR/AAL ordering — no LLD section
     * anywhere in this domain defines one, so only an exact match is ever
     * treated as satisfying a required level.
     */
    public boolean satisfies(String requiredAcr, List<String> requiredMethods) {
        boolean levelSatisfied = requiredAcr == null || requiredAcr.equals(acr);
        boolean methodsSatisfied = requiredMethods == null || requiredMethods.isEmpty() || amr.containsAll(requiredMethods);
        return levelSatisfied && methodsSatisfied;
    }
}
