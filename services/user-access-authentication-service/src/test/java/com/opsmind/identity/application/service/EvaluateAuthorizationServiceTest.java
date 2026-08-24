package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.EvaluateAuthorizationCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.infrastructure.audit.InMemoryAuditPort;
import com.opsmind.identity.infrastructure.hashing.Sha256HashingAdapter;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryAuthorizationDecisionRepository;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryRoleAssignmentRepository;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserIdentityRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class EvaluateAuthorizationServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final InMemoryRoleAssignmentRepository roleAssignmentRepository = new InMemoryRoleAssignmentRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), clock);
    private final ManageRoleAssignmentService roleAssignmentService = new ManageRoleAssignmentService(roleAssignmentRepository, userIdentityRepository, new InMemoryAuditPort(), clock);
    private final EvaluateAuthorizationService service = new EvaluateAuthorizationService(
        new InMemoryAuthorizationDecisionRepository(), userIdentityRepository, roleAssignmentRepository, new InMemoryAuditPort(), new Sha256HashingAdapter(), clock
    );

    private UserIdentity user() {
        return provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
    }

    @Test
    void deniesByDefaultWhenNoMatchingRoleAssignmentExists() {
        UserIdentity user = user();

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(decision.reasonCodes()).isNotEmpty();
    }

    @Test
    void allowsWhenAMatchingActiveRoleAssignmentExists() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), List.of(), null, "admin-1", null, "corr-grant"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);
    }

    @Test
    void isIdempotentOnDecisionKeyAndInputHash() {
        UserIdentity user = user();
        EvaluateAuthorizationCommand command = new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", null, null, "corr-1"
        );

        AuthorizationDecision first = service.evaluate(command);
        AuthorizationDecision second = service.evaluate(command);

        assertThat(second.decisionId()).isEqualTo(first.decisionId());
    }
}
