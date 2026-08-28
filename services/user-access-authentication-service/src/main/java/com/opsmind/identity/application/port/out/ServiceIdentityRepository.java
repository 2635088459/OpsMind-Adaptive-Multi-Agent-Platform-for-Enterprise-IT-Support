package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ServiceIdentityRepository {

    Optional<ServiceIdentity> findById(String serviceIdentityId);

    Optional<ServiceIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject);

    /** 03-state-machine: {@code ACTIVE} identities past their own {@code validUntil} — due for {@link ServiceIdentity#retire}. */
    List<ServiceIdentity> findActiveExpired(Instant now);

    ServiceIdentity save(ServiceIdentity serviceIdentity);
}
