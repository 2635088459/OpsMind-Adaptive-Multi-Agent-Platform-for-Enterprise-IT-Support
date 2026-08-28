package com.opsmind.identity.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.workload.ServiceIdentity;
import com.opsmind.identity.domain.workload.ServiceIdentityStatus;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.ServiceIdentityJpaEntity;

import java.util.List;

public final class ServiceIdentityMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private ServiceIdentityMapper() {
    }

    public static ServiceIdentityJpaEntity toEntity(ServiceIdentity identity) {
        return new ServiceIdentityJpaEntity(
            identity.serviceIdentityId(), identity.tenantId().value(), identity.externalSubject().issuer(), identity.externalSubject().subject(),
            identity.clientId(), identity.serviceName(), JsonSupport.writeList(identity.allowedAudiences()),
            JsonSupport.writeList(identity.allowedScopes()), identity.status().name(), identity.validFrom(), identity.validUntil(),
            identity.lastSeenAt(), identity.disabledAt(), identity.createdAt(), identity.updatedAt(),
            PersistenceVersion.entityVersion(identity.version()), identity.version() == 0
        );
    }

    public static ServiceIdentity toDomain(ServiceIdentityJpaEntity entity) {
        return ServiceIdentity.reconstruct(
            entity.getServiceIdentityId(), new TenantId(entity.getTenantId()), new ExternalSubject(entity.getIssuer(), entity.getSubject()),
            entity.getClientId(), entity.getServiceName(), JsonSupport.readList(entity.getAllowedAudiencesJson(), STRING_LIST),
            JsonSupport.readList(entity.getAllowedScopesJson(), STRING_LIST), ServiceIdentityStatus.valueOf(entity.getStatus()),
            entity.getValidFrom(), entity.getValidUntil(), entity.getLastSeenAt(), entity.getDisabledAt(), entity.getCreatedAt(),
            entity.getUpdatedAt(), entity.getVersion()
        );
    }
}
