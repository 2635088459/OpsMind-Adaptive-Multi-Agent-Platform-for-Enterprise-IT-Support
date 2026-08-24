package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.exception.RoleAssignmentNotFoundException;
import com.opsmind.identity.application.query.ListRoleAssignmentsQuery;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleAssignment;
import com.opsmind.identity.domain.role.RoleAssignmentStatus;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.infrastructure.audit.InMemoryAuditPort;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryRoleAssignmentRepository;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserIdentityRepository;
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
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), clock);
    private final ManageRoleAssignmentService service = new ManageRoleAssignmentService(new InMemoryRoleAssignmentRepository(), userIdentityRepository, new InMemoryAuditPort(), clock);

    private UserIdentity user() {
        return provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
    }

    @Test
    void grantThenListReflectsTheAssignment() {
        UserIdentity user = user();
        ResourceScope scope = new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing");

        RoleAssignment assignment = service.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, scope, List.of(), null, "admin-1", "onboarding", "corr-1"));

        assertThat(assignment.status()).isEqualTo(RoleAssignmentStatus.ACTIVE);
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId()))).containsExactly(assignment);
    }

    @Test
    void grantingTheSameRoleAndScopeTwiceIsIdempotent() {
        UserIdentity user = user();
        GrantRoleAssignmentCommand command = new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.APPROVER, ResourceScope.tenantWide(), List.of(), null, "admin-1", null, "corr-1");

        RoleAssignment first = service.grant(command);
        RoleAssignment second = service.grant(command);

        assertThat(second.roleAssignmentId()).isEqualTo(first.roleAssignmentId());
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId()))).hasSize(1);
    }

    @Test
    void revokeThenGrantAgainCreatesANewAssignment() {
        UserIdentity user = user();
        GrantRoleAssignmentCommand command = new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.IT_ADMIN, ResourceScope.tenantWide(), List.of(), null, "admin-1", null, "corr-1");

        RoleAssignment first = service.grant(command);
        service.revoke(new com.opsmind.identity.application.command.RevokeRoleAssignmentCommand(first.roleAssignmentId(), "admin-1", "no longer needed", "corr-2"));
        RoleAssignment second = service.grant(command);

        assertThat(second.roleAssignmentId()).isNotEqualTo(first.roleAssignmentId());
        assertThat(service.listForUser(new ListRoleAssignmentsQuery(user.userIdentityId()))).hasSize(2);
    }

    @Test
    void revokeThrowsWhenMissing() {
        assertThatThrownBy(() -> service.revoke(new com.opsmind.identity.application.command.RevokeRoleAssignmentCommand("missing", "admin-1", "reason", "corr-1")))
            .isInstanceOf(RoleAssignmentNotFoundException.class);
    }
}
