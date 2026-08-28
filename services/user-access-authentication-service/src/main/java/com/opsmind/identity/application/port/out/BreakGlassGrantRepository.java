package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BreakGlassGrantRepository {

    Optional<BreakGlassGrant> findById(String breakGlassGrantId);

    List<BreakGlassGrant> findByExternalSubject(String tenantId, ExternalSubject externalSubject);

    /** 04-use-cases §Break-glass: "Auto-expire" — {@code ACTIVE} grants past their own {@code expiresAt}, due for {@link BreakGlassGrant#expire}. */
    List<BreakGlassGrant> findActiveExpired(Instant now);

    /** SPEC-UA-028: correlates an async domain-06 approval-outcome event back to the grant it justified. Not unique — returns every match, oldest first. */
    List<BreakGlassGrant> findByApprovalReference(String approvalReference);

    BreakGlassGrant save(BreakGlassGrant grant);
}
