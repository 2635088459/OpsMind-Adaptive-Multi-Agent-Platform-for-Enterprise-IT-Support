package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.UserIdentity;

import java.util.Optional;

public interface UserIdentityRepository {

    Optional<UserIdentity> findById(String userIdentityId);

    /** {@code (tenantId, issuer, subject)} is the stable identity key (02-business-invariants). */
    Optional<UserIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject);

    UserIdentity save(UserIdentity userIdentity);
}
