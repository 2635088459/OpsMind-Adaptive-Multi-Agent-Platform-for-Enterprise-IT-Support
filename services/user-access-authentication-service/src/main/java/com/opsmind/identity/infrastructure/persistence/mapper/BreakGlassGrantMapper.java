package com.opsmind.identity.infrastructure.persistence.mapper;

import com.opsmind.identity.domain.breakglass.BreakGlassGrant;
import com.opsmind.identity.domain.breakglass.BreakGlassStatus;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.BreakGlassGrantJpaEntity;

public final class BreakGlassGrantMapper {

    private BreakGlassGrantMapper() {
    }

    public static BreakGlassGrantJpaEntity toEntity(BreakGlassGrant grant) {
        return new BreakGlassGrantJpaEntity(
            grant.breakGlassGrantId(), grant.tenantId().value(), grant.externalSubject().issuer(), grant.externalSubject().subject(),
            grant.scope().scopeType().name(), grant.scope().scopeId(), grant.approvalReference(), grant.reason(), grant.grantedBy(),
            grant.status().name(), grant.grantedAt(), grant.expiresAt(), grant.revokedBy(), grant.revokedAt(), grant.revocationReason(),
            grant.correlationId(), grant.createdAt(), grant.updatedAt(), PersistenceVersion.entityVersion(grant.version()), grant.version() == 0
        );
    }

    public static BreakGlassGrant toDomain(BreakGlassGrantJpaEntity entity) {
        return BreakGlassGrant.reconstruct(
            entity.getBreakGlassGrantId(), new TenantId(entity.getTenantId()), new ExternalSubject(entity.getIssuer(), entity.getSubject()),
            new ResourceScope(ResourceScope.ScopeType.valueOf(entity.getScopeType()), entity.getScopeId()), entity.getApprovalReference(),
            entity.getReason(), entity.getGrantedBy(), BreakGlassStatus.valueOf(entity.getStatus()), entity.getGrantedAt(),
            entity.getExpiresAt(), entity.getRevokedBy(), entity.getRevokedAt(), entity.getRevocationReason(), entity.getCorrelationId(),
            entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion()
        );
    }
}
