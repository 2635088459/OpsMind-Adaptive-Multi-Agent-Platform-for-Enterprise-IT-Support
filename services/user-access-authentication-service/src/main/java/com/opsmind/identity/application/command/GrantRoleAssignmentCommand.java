package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;

import java.time.Instant;
import java.util.List;

public record GrantRoleAssignmentCommand(
    String userIdentityId,
    String tenantId,
    RoleCode roleCode,
    ResourceScope scope,
    List<String> permissions,
    Instant validUntil,
    String grantedBy,
    String grantReason,
    String correlationId
) {
}
