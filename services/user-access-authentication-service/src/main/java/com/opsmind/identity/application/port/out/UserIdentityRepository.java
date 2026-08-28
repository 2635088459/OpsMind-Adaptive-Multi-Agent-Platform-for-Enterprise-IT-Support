package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository {

    Optional<UserIdentity> findById(String userIdentityId);

    /** {@code (tenantId, issuer, subject)} is the stable identity key (02-business-invariants). */
    Optional<UserIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject);

    UserIdentity save(UserIdentity userIdentity);

    /** SPEC-UA-031: {@code DEPROVISIONED} identities not yet PII-redacted whose {@code deprovisionedAt} is at or before {@code cutoff} — the retention reconciliation's own eligible set. */
    List<UserIdentity> findDeprovisionedDueForPiiRedaction(Instant cutoff);
}
