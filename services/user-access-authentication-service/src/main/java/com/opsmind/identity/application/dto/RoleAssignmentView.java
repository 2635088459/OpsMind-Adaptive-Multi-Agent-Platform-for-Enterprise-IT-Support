package com.opsmind.identity.application.dto;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleAssignmentStatus;
import com.opsmind.identity.domain.role.RoleCode;

import java.time.Instant;
import java.util.List;

public record RoleAssignmentView(
    String roleAssignmentId,
    String userIdentityId,
    String tenantId,
    RoleCode roleCode,
    ResourceScope scope,
    List<String> permissions,
    RoleAssignmentStatus status,
    Instant validFrom,
    Instant validUntil,
    String grantedBy,
    String revokedBy,
    Instant revokedAt
) {
    public static RoleAssignmentView from(RoleAssignment a) {
        return new RoleAssignmentView(
            a.roleAssignmentId(), a.userIdentityId(), a.tenantId().value(), a.roleCode(), a.scope(), a.permissions(),
            a.status(), a.validFrom(), a.validUntil(), a.grantedBy(), a.revokedBy(), a.revokedAt()
        );
    }
}
