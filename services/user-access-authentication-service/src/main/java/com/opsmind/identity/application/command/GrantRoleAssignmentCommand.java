package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;

import java.time.Instant;

/**
 * SPEC-UA-011 (Role And Permission Model): {@code permissions} is
 * deliberately not a field here — 05-api-contracts' own {@code POST
 * /role-assignments} request-field list names no such field, and
 * 02-business-invariants #7's own spirit (only verified claims and
 * server-side mappings produce authority) applies just as much to a
 * client-supplied permission list as to a client-supplied role/tenant/
 * subject. {@code ManageRoleAssignmentService#grant} derives the real
 * permission set from {@code roleCode} via {@code RolePermissionCatalog}
 * instead.
 */
public record GrantRoleAssignmentCommand(
    String userIdentityId,
    String tenantId,
    RoleCode roleCode,
    ResourceScope scope,
    /** {@code null} or not after "now" grants immediately ({@code ACTIVE}); a future instant grants {@code PENDING} (SPEC-UA-012, 03-state-machine). */
    Instant validFrom,
    Instant validUntil,
    String grantedBy,
    String grantReason,
    String correlationId
) {
}
