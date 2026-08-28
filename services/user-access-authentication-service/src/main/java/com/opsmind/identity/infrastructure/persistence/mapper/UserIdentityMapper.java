package com.opsmind.identity.infrastructure.persistence.mapper;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.UserIdentityJpaEntity;

public final class UserIdentityMapper {

    private UserIdentityMapper() {
    }

    public static UserIdentityJpaEntity toEntity(UserIdentity identity) {
        return new UserIdentityJpaEntity(
            identity.userIdentityId(), identity.tenantId().value(), identity.externalSubject().issuer(),
            identity.externalSubject().subject(), identity.username(), identity.displayName(), identity.email(),
            identity.identityType().name(), identity.status().name(), identity.profileVersion(), identity.linkedAt(),
            identity.lastSyncedAt(), identity.disabledAt(), identity.deprovisionedAt(), identity.piiRedactedAt(), identity.createdAt(),
            identity.updatedAt(), PersistenceVersion.entityVersion(identity.version()), identity.version() == 0
        );
    }

    public static UserIdentity toDomain(UserIdentityJpaEntity entity) {
        return UserIdentity.reconstruct(
            entity.getUserIdentityId(), new TenantId(entity.getTenantId()), new ExternalSubject(entity.getIssuer(), entity.getSubject()),
            entity.getUsername(), entity.getDisplayName(), entity.getEmail(), IdentityType.valueOf(entity.getIdentityType()),
            UserStatus.valueOf(entity.getStatus()), entity.getProfileVersion(), entity.getLinkedAt(), entity.getLastSyncedAt(),
            entity.getDisabledAt(), entity.getDeprovisionedAt(), entity.getPiiRedactedAt(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion()
        );
    }
}
