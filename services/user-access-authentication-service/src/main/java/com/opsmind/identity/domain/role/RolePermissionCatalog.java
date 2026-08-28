package com.opsmind.identity.domain.role;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 01-domain-model §RoleAssignment ("permissions"). {@link RoleCode}'s own
 * javadoc names this class's job directly: "the full role/permission model
 * is SPEC-UA-011's job." A fixed, server-side-authoritative RoleCode →
 * permission-set table — {@link RoleAssignment#permissions()} is always
 * derived from here at grant time (see {@code
 * ManageRoleAssignmentService#grant}), never trusted from client input
 * (02-business-invariants #7's own spirit: only verified claims and
 * server-side mappings produce authority — a client-supplied permission
 * list is exactly the kind of thing that spirit rules out).
 *
 * <p>Deliberately scoped to only the three permission strings this domain's
 * own LLD names anywhere — 05-api-contracts' own admin-authority column
 * ({@code identity:role:grant}, {@code identity:role:revoke}, {@code
 * identity:user:admin}). Domain 01 supplies identity-level facts only, never
 * another domain's business permissions (01-domain-model §Boundary and
 * ownership: "Ticket, Workflow, Tool, Memory, and governance Policy state
 * remain owned by domains 02-06") — inventing a richer catalog here would be
 * fabricating scope no LLD section actually names.
 */
public final class RolePermissionCatalog {

    public static final String ROLE_GRANT = "identity:role:grant";
    public static final String ROLE_REVOKE = "identity:role:revoke";
    public static final String USER_ADMIN = "identity:user:admin";

    private static final Map<RoleCode, Set<String>> PERMISSIONS_BY_ROLE = buildTable();

    private RolePermissionCatalog() {
    }

    private static Map<RoleCode, Set<String>> buildTable() {
        Map<RoleCode, Set<String>> table = new EnumMap<>(RoleCode.class);
        table.put(RoleCode.EMPLOYEE, Set.of());
        table.put(RoleCode.SUPPORT_AGENT, Set.of());
        table.put(RoleCode.APPROVER, Set.of());
        table.put(RoleCode.AUDITOR, Set.of());
        // Day-to-day user lifecycle management, not role-granting authority.
        table.put(RoleCode.IT_ADMIN, Set.of(USER_ADMIN));
        table.put(RoleCode.PLATFORM_ADMIN, Set.of(ROLE_GRANT, ROLE_REVOKE, USER_ADMIN));
        return Map.copyOf(table);
    }

    public static Set<String> permissionsFor(RoleCode roleCode) {
        return PERMISSIONS_BY_ROLE.getOrDefault(roleCode, Set.of());
    }
}
