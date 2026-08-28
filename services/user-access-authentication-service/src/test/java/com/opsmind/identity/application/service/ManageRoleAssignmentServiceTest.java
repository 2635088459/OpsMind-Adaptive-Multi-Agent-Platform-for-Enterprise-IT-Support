package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.CancelRoleAssignmentCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.RevokeRoleAssignmentCommand;
import com.opsmind.identity.application.dto.RoleAssignmentReconciliationResult;
import com.opsmind.identity.application.exception.RoleAssignmentNotFoundException;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.role.IllegalRoleAssignmentTransitionException;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleAssignmentStatus;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryRoleAssignmentRepository;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ManageRoleAssignmentServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final com.opsmind.identity.support.InMemoryIdentityMetricsPort identityMetricsPort = new com.opsmind.identity.support.InMemoryIdentityMetricsPort();
    private final ManageRoleAssignmentService service = new ManageRoleAssignmentService(new InMemoryRoleAssignmentRepository(), userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), identityMetricsPort, clock);

    private UserIdentity user() {
        return provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
    }

    @Test
    void grantThenListReflectsTheAssignment() {
        UserIdentity user = user();
        ResourceScope scope = new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing");

        RoleAssignment assignment = service.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, scope, null, null, "admin-1", "onboarding", "corr-1"));

        assertThat(assignment.status()).isEqualTo(RoleAssignmentStatus.ACTIVE);
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId()))).containsExactly(assignment);
        assertThat(identityMetricsPort.roleAssignmentChanges()).containsExactly("GRANTED");
    }

    /** SPEC-UA-011: permissions are always the server-side RolePermissionCatalog's own set for the granted roleCode — there is no client input to derive them from any more. */
    @Test
    void grantDerivesPermissionsFromTheServerSideRolePermissionCatalog() {
        UserIdentity user = user();

        RoleAssignment platformAdmin = service.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.PLATFORM_ADMIN, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-1"));
        RoleAssignment employee = service.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, "admin-1", null, "corr-2"));

        assertThat(platformAdmin.permissions()).containsExactlyInAnyOrder(
            com.opsmind.identity.domain.role.RolePermissionCatalog.ROLE_GRANT,
            com.opsmind.identity.domain.role.RolePermissionCatalog.ROLE_REVOKE,
            com.opsmind.identity.domain.role.RolePermissionCatalog.USER_ADMIN
        );
        assertThat(employee.permissions()).isEmpty();
    }

    @Test
    void grantingTheSameRoleAndScopeTwiceIsIdempotent() {
        UserIdentity user = user();
        GrantRoleAssignmentCommand command = new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.APPROVER, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-1");

        RoleAssignment first = service.grant(command);
        RoleAssignment second = service.grant(command);

        assertThat(second.roleAssignmentId()).isEqualTo(first.roleAssignmentId());
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId()))).hasSize(1);
    }

    @Test
    void revokeThenGrantAgainCreatesANewAssignment() {
        UserIdentity user = user();
        GrantRoleAssignmentCommand command = new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.IT_ADMIN, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-1");

        RoleAssignment first = service.grant(command);
        service.revoke(new RevokeRoleAssignmentCommand(first.roleAssignmentId(), "admin-1", "no longer needed", "corr-2"));
        RoleAssignment second = service.grant(command);

        assertThat(second.roleAssignmentId()).isNotEqualTo(first.roleAssignmentId());
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId()))).hasSize(2);
        assertThat(identityMetricsPort.roleAssignmentChanges()).containsExactly("GRANTED", "REVOKED", "GRANTED");
    }

    @Test
    void revokeThrowsWhenMissing() {
        assertThatThrownBy(() -> service.revoke(new RevokeRoleAssignmentCommand("missing", "admin-1", "reason", "corr-1")))
            .isInstanceOf(RoleAssignmentNotFoundException.class);
    }

    @Test
    void grantWithAFutureValidFromStartsPendingAndReconcileActivatesItOnceDue() {
        UserIdentity user = user();
        Instant validFrom = clock.now().plusSeconds(3600);
        RoleAssignment pending = service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), validFrom, null, "admin-1", "scheduled", "corr-1"
        ));
        assertThat(pending.status()).isEqualTo(RoleAssignmentStatus.PENDING);

        RoleAssignmentReconciliationResult beforeDue = service.reconcileDueTransitions();
        assertThat(beforeDue.activatedCount()).isZero();

        clock.advanceTo(validFrom);
        RoleAssignmentReconciliationResult onceDue = service.reconcileDueTransitions();
        assertThat(onceDue.activatedCount()).isEqualTo(1);
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId())).get(0).status()).isEqualTo(RoleAssignmentStatus.ACTIVE);
    }

    @Test
    void reconcileExpiresActiveAssignmentsPastValidUntil() {
        UserIdentity user = user();
        Instant validUntil = clock.now().plusSeconds(60);
        service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.AUDITOR, ResourceScope.tenantWide(), null, validUntil, "admin-1", null, "corr-1"
        ));

        clock.advanceTo(validUntil);
        RoleAssignmentReconciliationResult result = service.reconcileDueTransitions();

        assertThat(result.expiredCount()).isEqualTo(1);
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId())).get(0).status()).isEqualTo(RoleAssignmentStatus.EXPIRED);
    }

    @Test
    void cancelIsLegalOnlyOnAPendingGrant() {
        UserIdentity user = user();
        RoleAssignment pending = service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), clock.now().plusSeconds(3600), null, "admin-1", null, "corr-1"
        ));

        RoleAssignment cancelled = service.cancel(new CancelRoleAssignmentCommand(pending.roleAssignmentId(), "admin-1", "no longer needed", "corr-2"));
        assertThat(cancelled.status()).isEqualTo(RoleAssignmentStatus.CANCELLED);

        RoleAssignment activeGrant = service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.APPROVER, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-3"
        ));
        assertThatThrownBy(() -> service.cancel(new CancelRoleAssignmentCommand(activeGrant.roleAssignmentId(), "admin-1", "too late", "corr-4")))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);
    }

    /** SPEC-UA-007 (05-api-contracts {@code GET /users/me}: "effective roles/scopes"). */
    @Test
    void listEffectiveForUserExcludesPendingExpiredRevokedAndCancelledAssignments() {
        UserIdentity user = user();
        RoleAssignment active = service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing"),
            null, null, "admin-1", null, "corr-1"
        ));
        RoleAssignment pending = service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.APPROVER, ResourceScope.tenantWide(), clock.now().plusSeconds(3600), null, "admin-1", null, "corr-2"
        ));
        RoleAssignment toRevoke = service.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.IT_ADMIN, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-3"
        ));
        service.revoke(new RevokeRoleAssignmentCommand(toRevoke.roleAssignmentId(), "admin-1", "no longer needed", "corr-4"));

        List<RoleAssignment> effective = service.listEffectiveForUser(new ListRoleAssignmentsQuery(user.userIdentityId()));

        assertThat(effective).extracting(RoleAssignment::roleAssignmentId).containsExactly(active.roleAssignmentId());
        assertThat(effective).extracting(RoleAssignment::status).containsOnly(RoleAssignmentStatus.ACTIVE);
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId())))
            .extracting(RoleAssignment::roleAssignmentId).containsExactlyInAnyOrder(
                active.roleAssignmentId(), pending.roleAssignmentId(), toRevoke.roleAssignmentId()
            );
    }
}
