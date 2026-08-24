package com.opsmind.identity.domain.workload;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A workload/service identity (01-domain-model §ServiceIdentity;
 * 02-business-invariants: "Workloads use client credentials or mTLS with
 * separate audiences/scopes and cannot impersonate a human `sub`"). Client
 * secrets and private keys are never stored here (INV-UA-001) — only the
 * trust metadata used to validate a workload token. Real client-credentials/
 * mTLS validation is SPEC-UA-010's job (Workload And Service Identity).
 */
public final class ServiceIdentity {

    private final String serviceIdentityId;
    private final TenantId tenantId;
    private final ExternalSubject externalSubject;
    private final String clientId;
    private final String serviceName;
    private final List<String> allowedAudiences;
    private final List<String> allowedScopes;
    private final ServiceIdentityStatus status;
    private final Instant validFrom;
    private final Instant validUntil;
    private final Instant lastSeenAt;
    private final Instant disabledAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private ServiceIdentity(
        String serviceIdentityId, TenantId tenantId, ExternalSubject externalSubject, String clientId, String serviceName,
        List<String> allowedAudiences, List<String> allowedScopes, ServiceIdentityStatus status, Instant validFrom,
        Instant validUntil, Instant lastSeenAt, Instant disabledAt, Instant createdAt, Instant updatedAt, long version
    ) {
        this.serviceIdentityId = Objects.requireNonNull(serviceIdentityId, "serviceIdentityId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.allowedAudiences = List.copyOf(allowedAudiences == null ? List.of() : allowedAudiences);
        this.allowedScopes = List.copyOf(allowedScopes == null ? List.of() : allowedScopes);
        this.status = Objects.requireNonNull(status, "status");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.lastSeenAt = lastSeenAt;
        this.disabledAt = disabledAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static ServiceIdentity register(
        String serviceIdentityId, TenantId tenantId, ExternalSubject externalSubject, String clientId, String serviceName,
        List<String> allowedAudiences, List<String> allowedScopes, Instant validFrom, Instant validUntil, Instant now
    ) {
        return new ServiceIdentity(
            serviceIdentityId, tenantId, externalSubject, clientId, serviceName, allowedAudiences, allowedScopes,
            ServiceIdentityStatus.ACTIVE, validFrom, validUntil, null, null, now, now, 0L
        );
    }

    /** Rehydrates a previously-persisted identity. Used only by a future persistence mapper (SPEC-UA-002). */
    public static ServiceIdentity reconstruct(
        String serviceIdentityId, TenantId tenantId, ExternalSubject externalSubject, String clientId, String serviceName,
        List<String> allowedAudiences, List<String> allowedScopes, ServiceIdentityStatus status, Instant validFrom,
        Instant validUntil, Instant lastSeenAt, Instant disabledAt, Instant createdAt, Instant updatedAt, long version
    ) {
        return new ServiceIdentity(
            serviceIdentityId, tenantId, externalSubject, clientId, serviceName, allowedAudiences, allowedScopes,
            status, validFrom, validUntil, lastSeenAt, disabledAt, createdAt, updatedAt, version
        );
    }

    public ServiceIdentity disable(Instant now) {
        if (status != ServiceIdentityStatus.ACTIVE) {
            throw new IllegalServiceIdentityTransitionException(status, ServiceIdentityStatus.DISABLED);
        }
        return new ServiceIdentity(
            serviceIdentityId, tenantId, externalSubject, clientId, serviceName, allowedAudiences, allowedScopes,
            ServiceIdentityStatus.DISABLED, validFrom, validUntil, lastSeenAt, now, createdAt, now, version + 1
        );
    }

    /** Legal from {@code ACTIVE} or {@code DISABLED}; reconciliation retires a still-{@code ACTIVE} identity past its own {@code validUntil} directly. */
    public ServiceIdentity retire(Instant now) {
        if (status == ServiceIdentityStatus.RETIRED) {
            throw new IllegalServiceIdentityTransitionException(status, ServiceIdentityStatus.RETIRED);
        }
        return new ServiceIdentity(
            serviceIdentityId, tenantId, externalSubject, clientId, serviceName, allowedAudiences, allowedScopes,
            ServiceIdentityStatus.RETIRED, validFrom, validUntil, lastSeenAt, disabledAt, createdAt, now, version + 1
        );
    }

    public ServiceIdentity recordSeen(Instant now) {
        return new ServiceIdentity(
            serviceIdentityId, tenantId, externalSubject, clientId, serviceName, allowedAudiences, allowedScopes,
            status, validFrom, validUntil, now, disabledAt, createdAt, now, version + 1
        );
    }

    public boolean isValid(Instant now) {
        return status == ServiceIdentityStatus.ACTIVE
            && (validFrom == null || !now.isBefore(validFrom))
            && (validUntil == null || now.isBefore(validUntil));
    }

    public String serviceIdentityId() {
        return serviceIdentityId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public ExternalSubject externalSubject() {
        return externalSubject;
    }

    public String clientId() {
        return clientId;
    }

    public String serviceName() {
        return serviceName;
    }

    public List<String> allowedAudiences() {
        return allowedAudiences;
    }

    public List<String> allowedScopes() {
        return allowedScopes;
    }

    public ServiceIdentityStatus status() {
        return status;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    public Instant disabledAt() {
        return disabledAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
