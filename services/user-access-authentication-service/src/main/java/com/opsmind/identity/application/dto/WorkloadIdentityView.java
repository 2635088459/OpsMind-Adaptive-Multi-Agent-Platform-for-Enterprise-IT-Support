package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;

import java.time.Instant;

public record WorkloadIdentityView(
    String serviceIdentityId,
    String tenantId,
    String clientId,
    String serviceName,
    ServiceIdentityStatus status,
    Instant validatedAt
) {
    public static WorkloadIdentityView from(ServiceIdentity s) {
        return new WorkloadIdentityView(s.serviceIdentityId(), s.tenantId().value(), s.clientId(), s.serviceName(), s.status(), s.lastSeenAt());
    }
}
