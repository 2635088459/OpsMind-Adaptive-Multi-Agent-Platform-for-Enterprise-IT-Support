package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;

import java.time.Instant;
import java.util.List;

public record ServiceIdentityView(
    String serviceIdentityId,
    String tenantId,
    String issuer,
    String subject,
    String clientId,
    String serviceName,
    List<String> allowedAudiences,
    List<String> allowedScopes,
    ServiceIdentityStatus status,
    Instant validFrom,
    Instant validUntil
) {
    public static ServiceIdentityView from(ServiceIdentity s) {
        return new ServiceIdentityView(
            s.serviceIdentityId(), s.tenantId().value(), s.externalSubject().issuer(), s.externalSubject().subject(),
            s.clientId(), s.serviceName(), s.allowedAudiences(), s.allowedScopes(), s.status(), s.validFrom(), s.validUntil()
        );
    }
}
