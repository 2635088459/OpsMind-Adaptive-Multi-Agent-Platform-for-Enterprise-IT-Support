package com.opsmind.identity.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleAssignmentStatus;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.infrastructure.persistence.jpa.entity.RoleAssignmentJpaEntity;

import java.util.List;

public final class RoleAssignmentMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private RoleAssignmentMapper() {
    }

    public static RoleAssignmentJpaEntity toEntity(RoleAssignment assignment) {
        return new RoleAssignmentJpaEntity(
            assignment.roleAssignmentId(), assignment.tenantId().value(), assignment.userIdentityId(), assignment.roleCode().name(),
            assignment.scope().scopeType().name(), assignment.scope().scopeId(), JsonSupport.writeList(assignment.permissions()),
            assignment.status().name(), assignment.validFrom(), assignment.validUntil(), assignment.grantedBy(), assignment.grantReason(),
            assignment.revokedBy(), assignment.revokedAt(), assignment.revocationReason(), assignment.createdAt(), assignment.updatedAt(),
            PersistenceVersion.entityVersion(assignment.version()), assignment.version() == 0
        );
    }

    public static RoleAssignment toDomain(RoleAssignmentJpaEntity entity) {
        return RoleAssignment.reconstruct(
            entity.getRoleAssignmentId(), new TenantId(entity.getTenantId()), entity.getUserIdentityId(), RoleCode.valueOf(entity.getRoleCode()),
            new ResourceScope(ResourceScope.ScopeType.valueOf(entity.getScopeType()), entity.getScopeId()),
            JsonSupport.readList(entity.getPermissionsJson(), STRING_LIST), RoleAssignmentStatus.valueOf(entity.getStatus()),
            entity.getValidFrom(), entity.getValidUntil(), entity.getGrantedBy(), entity.getGrantReason(), entity.getRevokedBy(),
            entity.getRevokedAt(), entity.getRevocationReason(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion()
        );
    }
}
