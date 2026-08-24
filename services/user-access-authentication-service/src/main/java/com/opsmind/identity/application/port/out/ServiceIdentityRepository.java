package com.opsmind.identity.application.port.out;

import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;

import java.util.Optional;

public interface ServiceIdentityRepository {

    Optional<ServiceIdentity> findById(String serviceIdentityId);

    Optional<ServiceIdentity> findByExternalSubject(String tenantId, ExternalSubject externalSubject);

    ServiceIdentity save(ServiceIdentity serviceIdentity);
}
