package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.RequireIdentityPermissionCommand;
import com.opsmind.identity.application.command.RequireRoleGrantWithinScopeCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.exception.PermissionDeniedException;
import com.opsmind.identity.application.exception.RoleGrantOverreachException;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RolePermissionCatalog;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryRoleAssignmentRepository;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-UA-011 (Role And Permission Model) — the real per-endpoint RBAC gate. */
@Tag("unit")
class EnforceIdentityPermissionServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final InMemoryRoleAssignmentRepository roleAssignmentRepository = new InMemoryRoleAssignmentRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final ManageRoleAssignmentService roleAssignmentService = new ManageRoleAssignmentService(roleAssignmentRepository, userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), new com.opsmind.identity.support.InMemoryIdentityMetricsPort(), clock);
    private final EnforceIdentityPermissionService service = new EnforceIdentityPermissionService(userIdentityRepository, roleAssignmentRepository, new InMemoryAuditPort(), clock);

    private UserIdentity user() {
        return provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
    }

    private RequireIdentityPermissionCommand command(String requiredPermission) {
        return new RequireIdentityPermissionCommand("tenant-1", "https://idp.example", "sub-1", requiredPermission, "corr-1");
    }

    private RequireRoleGrantWithinScopeCommand grantScopeCommand(RoleCode targetRoleCode) {
        return new RequireRoleGrantWithinScopeCommand("tenant-1", "https://idp.example", "sub-1", targetRoleCode, "corr-1");
    }

    @Test
    void allowsWhenAnActiveRoleAssignmentGrantsThePermission() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.PLATFORM_ADMIN, ResourceScope.tenantWide(), null, null, "admin-0", null, "corr-grant"));

        service.require(command(RolePermissionCatalog.ROLE_GRANT));
        // no exception
    }

    @Test
    void deniesWhenNoUserIdentityExistsForTheSubject() {
        assertThatThrownBy(() -> service.require(command(RolePermissionCatalog.ROLE_GRANT)))
            .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void deniesWhenTheUserIdentityIsNotActive() {
        UserIdentity user = user();
        provisionUserService.changeStatus(new ChangeUserIdentityStatusCommand(user.userIdentityId(), UserStatus.DISABLED, "policy", "corr-disable"));

        assertThatThrownBy(() -> service.require(command(RolePermissionCatalog.ROLE_GRANT)))
            .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void deniesWhenNoActiveRoleAssignmentGrantsThePermission() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, "admin-0", null, "corr-grant"));

        assertThatThrownBy(() -> service.require(command(RolePermissionCatalog.ROLE_GRANT)))
            .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void deniesOnceTheGrantingRoleAssignmentIsRevoked() {
        UserIdentity user = user();
        RoleAssignment granted = roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.IT_ADMIN, ResourceScope.tenantWide(), null, null, "admin-0", null, "corr-grant"));
        service.require(command(RolePermissionCatalog.USER_ADMIN));

        roleAssignmentService.revoke(new RevokeRoleAssignmentCommand(granted.roleAssignmentId(), "admin-0", "no longer needed", "corr-revoke"));

        assertThatThrownBy(() -> service.require(command(RolePermissionCatalog.USER_ADMIN)))
            .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void aRoleGrantingOnePermissionDoesNotImplyAnother() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.IT_ADMIN, ResourceScope.tenantWide(), null, null, "admin-0", null, "corr-grant"));

        service.require(command(RolePermissionCatalog.USER_ADMIN));
        assertThatThrownBy(() -> service.require(command(RolePermissionCatalog.ROLE_GRANT)))
            .isInstanceOf(PermissionDeniedException.class);
    }

    /** SPEC-UA-012 (02-business-invariants #9: "A role grantor cannot delegate beyond its own grant scope"). */
    @Test
    void aPlatformAdminGrantorCanGrantAnyRoleSinceItHoldsEveryIdentityPermission() {
        UserIdentity grantor = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(grantor.userIdentityId(), "tenant-1", RoleCode.PLATFORM_ADMIN, ResourceScope.tenantWide(), null, null, "admin-0", null, "corr-grant"));

        service.requireGrantWithinScope(grantScopeCommand(RoleCode.PLATFORM_ADMIN));
        service.requireGrantWithinScope(grantScopeCommand(RoleCode.IT_ADMIN));
        service.requireGrantWithinScope(grantScopeCommand(RoleCode.EMPLOYEE));
        // no exception for any of them
    }

    @Test
    void anItAdminGrantorCanGrantItsOwnRoleButNotAHigherOne() {
        UserIdentity grantor = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(grantor.userIdentityId(), "tenant-1", RoleCode.IT_ADMIN, ResourceScope.tenantWide(), null, null, "admin-0", null, "corr-grant"));

        service.requireGrantWithinScope(grantScopeCommand(RoleCode.IT_ADMIN));
        assertThatThrownBy(() -> service.requireGrantWithinScope(grantScopeCommand(RoleCode.PLATFORM_ADMIN)))
            .isInstanceOf(RoleGrantOverreachException.class);
    }

    @Test
    void aGrantorWithNoIdentityAdminPermissionsCanStillGrantAPermissionlessRole() {
        UserIdentity grantor = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(grantor.userIdentityId(), "tenant-1", RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, "admin-0", null, "corr-grant"));

        service.requireGrantWithinScope(grantScopeCommand(RoleCode.SUPPORT_AGENT));
        // EMPLOYEE and SUPPORT_AGENT both carry an empty permission set — the empty set is always a subset.
    }

    @Test
    void requireGrantWithinScopeDeniesWhenNoUserIdentityExistsForTheSubject() {
        assertThatThrownBy(() -> service.requireGrantWithinScope(grantScopeCommand(RoleCode.EMPLOYEE)))
            .isInstanceOf(RoleGrantOverreachException.class);
    }
}
