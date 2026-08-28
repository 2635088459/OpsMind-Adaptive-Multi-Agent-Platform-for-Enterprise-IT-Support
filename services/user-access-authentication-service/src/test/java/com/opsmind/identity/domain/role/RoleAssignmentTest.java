package com.opsmind.identity.domain.role;

import com.opsmind.identity.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ResourceScope SCOPE = new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing");

    private RoleAssignment grant() {
        return RoleAssignment.grantActive(
            "ra-1", new TenantId("tenant-1"), "u-1", RoleCode.SUPPORT_AGENT, SCOPE, List.of("ticket:read"), null, "admin-1", "onboarding", NOW
        );
    }

    @Test
    void grantActiveStartsActive() {
        RoleAssignment assignment = grant();

        assertThat(assignment.status()).isEqualTo(RoleAssignmentStatus.ACTIVE);
        assertThat(assignment.isActive(NOW)).isTrue();
    }

    @Test
    void matchesIsTrueOnlyForTheSameRoleAndScopeWhileActive() {
        RoleAssignment assignment = grant();

        assertThat(assignment.matches(RoleCode.SUPPORT_AGENT, SCOPE, NOW)).isTrue();
        assertThat(assignment.matches(RoleCode.APPROVER, SCOPE, NOW)).isFalse();
        assertThat(assignment.matches(RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), NOW)).isFalse();
    }

    @Test
    void expiresPastValidUntil() {
        RoleAssignment timeLimited = RoleAssignment.grantActive(
            "ra-2", new TenantId("tenant-1"), "u-1", RoleCode.APPROVER, ResourceScope.tenantWide(),
            List.of(), NOW.plusSeconds(3600), "admin-1", null, NOW
        );

        assertThat(timeLimited.isActive(NOW.plusSeconds(1))).isTrue();
        assertThat(timeLimited.isActive(NOW.plusSeconds(3601))).isFalse();
    }

    @Test
    void revokeIsIllegalTwice() {
        RoleAssignment revoked = grant().revoke("admin-1", "no longer needed", NOW.plusSeconds(60));

        assertThat(revoked.status()).isEqualTo(RoleAssignmentStatus.REVOKED);
        assertThatThrownBy(() -> revoked.revoke("admin-1", "again", NOW.plusSeconds(120)))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);
    }

    @Test
    void grantPendingStartsPendingAndRejectsAPastValidFrom() {
        RoleAssignment pending = RoleAssignment.grantPending(
            "ra-3", new TenantId("tenant-1"), "u-1", RoleCode.SUPPORT_AGENT, SCOPE, List.of(),
            NOW.plusSeconds(3600), null, "admin-1", "scheduled onboarding", NOW
        );

        assertThat(pending.status()).isEqualTo(RoleAssignmentStatus.PENDING);
        assertThat(pending.isActive(NOW)).isFalse();
        assertThatThrownBy(() -> RoleAssignment.grantPending(
            "ra-4", new TenantId("tenant-1"), "u-1", RoleCode.SUPPORT_AGENT, SCOPE, List.of(), NOW, null, "admin-1", null, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activateTransitionsPendingToActiveOnlyOnceValidFromIsReached() {
        RoleAssignment pending = RoleAssignment.grantPending(
            "ra-3", new TenantId("tenant-1"), "u-1", RoleCode.SUPPORT_AGENT, SCOPE, List.of(),
            NOW.plusSeconds(3600), null, "admin-1", "scheduled onboarding", NOW
        );

        assertThatThrownBy(() -> pending.activate(NOW.plusSeconds(1)))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);

        RoleAssignment activated = pending.activate(NOW.plusSeconds(3600));
        assertThat(activated.status()).isEqualTo(RoleAssignmentStatus.ACTIVE);
        assertThat(activated.isActive(NOW.plusSeconds(3600))).isTrue();
        assertThatThrownBy(() -> activated.activate(NOW.plusSeconds(3600)))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);
    }

    @Test
    void cancelIsLegalOnlyFromPending() {
        RoleAssignment pending = RoleAssignment.grantPending(
            "ra-3", new TenantId("tenant-1"), "u-1", RoleCode.SUPPORT_AGENT, SCOPE, List.of(),
            NOW.plusSeconds(3600), null, "admin-1", "scheduled onboarding", NOW
        );

        RoleAssignment cancelled = pending.cancel("admin-1", "no longer needed", NOW.plusSeconds(10));
        assertThat(cancelled.status()).isEqualTo(RoleAssignmentStatus.CANCELLED);
        assertThat(cancelled.revokedBy()).isEqualTo("admin-1");

        assertThatThrownBy(() -> grant().cancel("admin-1", "too late", NOW.plusSeconds(60)))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);
        assertThatThrownBy(() -> cancelled.cancel("admin-1", "again", NOW.plusSeconds(20)))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);
    }

    @Test
    void expireIsIllegalFromNonActive() {
        RoleAssignment pending = RoleAssignment.grantPending(
            "ra-3", new TenantId("tenant-1"), "u-1", RoleCode.SUPPORT_AGENT, SCOPE, List.of(),
            NOW.plusSeconds(3600), null, "admin-1", null, NOW
        );

        assertThatThrownBy(() -> pending.expire(NOW.plusSeconds(3600)))
            .isInstanceOf(IllegalRoleAssignmentTransitionException.class);

        RoleAssignment expired = grant().expire(NOW.plusSeconds(60));
        assertThat(expired.status()).isEqualTo(RoleAssignmentStatus.EXPIRED);
    }
}
