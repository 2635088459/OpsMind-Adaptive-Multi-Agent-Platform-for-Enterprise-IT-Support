package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.EvaluateAuthorizationCommand;
import com.opsmind.identity.application.command.GrantRoleAssignmentCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.domain.decision.AuthorizationDecision;
import com.opsmind.identity.domain.decision.DecisionEffect;
import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.role.RoleCode;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.support.FakeOidcProviderPort;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.infrastructure.hashing.Sha256HashingAdapter;
import com.opsmind.identity.support.InMemoryAuthorizationDecisionRepository;
import com.opsmind.identity.support.InMemoryRoleAssignmentRepository;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import com.opsmind.identity.support.InMemoryUserSessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class EvaluateAuthorizationServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final InMemoryRoleAssignmentRepository roleAssignmentRepository = new InMemoryRoleAssignmentRepository();
    private final InMemoryUserSessionRepository userSessionRepository = new InMemoryUserSessionRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final ManageRoleAssignmentService roleAssignmentService = new ManageRoleAssignmentService(roleAssignmentRepository, userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), new com.opsmind.identity.support.InMemoryIdentityMetricsPort(), clock);
    private final ManageSessionService sessionService = new ManageSessionService(
        userSessionRepository, userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), new FakeOidcProviderPort(),
        new com.opsmind.identity.support.InMemoryIdentityMetricsPort(), clock
    );
    private final com.opsmind.identity.support.InMemoryIdentityMetricsPort identityMetricsPort = new com.opsmind.identity.support.InMemoryIdentityMetricsPort();
    private final EvaluateAuthorizationService service = new EvaluateAuthorizationService(
        new InMemoryAuthorizationDecisionRepository(), userIdentityRepository, roleAssignmentRepository, userSessionRepository,
        new InMemoryAuditPort(), new Sha256HashingAdapter(), identityMetricsPort, clock
    );

    private UserIdentity user() {
        return provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
    }

    private UserSession sessionWithAssurance(String acr, List<String> amr) {
        return sessionService.start(new StartSessionCommand(
            "tenant-1", "https://idp.example", "sub-1", "idp-hash", "token-hash", "client-1", acr, amr,
            clock.now(), "device-hash", Duration.ofHours(1), "corr-session"
        ));
    }

    @Test
    void deniesByDefaultWhenNoMatchingRoleAssignmentExists() {
        UserIdentity user = user();

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            ResourceScope.tenantWide(), null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
        assertThat(decision.reasonCodes()).isNotEmpty();
        assertThat(identityMetricsPort.authorizationDecisions()).containsExactly("DENY");
    }

    @Test
    void allowsWhenAMatchingActiveRoleAssignmentExists() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-grant"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            ResourceScope.tenantWide(), null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);
        assertThat(identityMetricsPort.authorizationDecisions()).containsExactly("ALLOW");
    }

    @Test
    void isIdempotentOnDecisionKeyAndInputHash() {
        UserIdentity user = user();
        EvaluateAuthorizationCommand command = new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", null, null, null, null, null, "corr-1"
        );

        AuthorizationDecision first = service.evaluate(command);
        AuthorizationDecision second = service.evaluate(command);

        assertThat(second.decisionId()).isEqualTo(first.decisionId());
    }

    /** SPEC-UA-014 (Authorization Context And Decision API) — a broader TENANT grant satisfies a narrower SUPPORT_QUEUE requirement. */
    @Test
    void aTenantWideGrantSatisfiesANarrowerSupportQueueRequirement() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-grant"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing"), null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);
    }

    @Test
    void aSupportQueueGrantNeverSatisfiesABroaderTenantRequirement() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing"), null, null, "admin-1", null, "corr-grant"
        ));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            ResourceScope.tenantWide(), null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
    }

    @Test
    void aSupportQueueGrantNeverSatisfiesADifferentQueue() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "billing"), null, null, "admin-1", null, "corr-grant"
        ));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            new ResourceScope(ResourceScope.ScopeType.SUPPORT_QUEUE, "sales"), null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
    }

    @Test
    void aTenantWideGrantNeverSatisfiesASelfRequirementSinceOwnershipIsADifferentAxis() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-grant"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
    }

    /** SPEC-UA-015 (Self Service And Resource Ownership — 02-business-invariants #6). */
    @Test
    void aSelfScopedGrantAllowsOnlyWhenTheAssertedResourceOwnerMatchesTheVerifiedSubject() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, "admin-1", null, "corr-grant"
        ));

        AuthorizationDecision allowed = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "profile:read", "profile", "p-1", RoleCode.EMPLOYEE,
            new ResourceScope(ResourceScope.ScopeType.SELF, null), user.userIdentityId(), null, null, "corr-1"
        ));
        assertThat(allowed.effect()).isEqualTo(DecisionEffect.ALLOW);
        assertThat(allowed.ownershipSatisfied()).isTrue();

        AuthorizationDecision deniedForSomeoneElse = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "profile:read", "profile", "p-2", RoleCode.EMPLOYEE,
            new ResourceScope(ResourceScope.ScopeType.SELF, null), "some-other-user-id", null, null, "corr-2"
        ));
        assertThat(deniedForSomeoneElse.effect()).isEqualTo(DecisionEffect.DENY);

        AuthorizationDecision deniedWithNoOwnershipAssertionAtAll = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "profile:read", "profile", "p-3", RoleCode.EMPLOYEE,
            new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, null, "corr-3"
        ));
        assertThat(deniedWithNoOwnershipAssertionAtAll.effect()).isEqualTo(DecisionEffect.DENY);
    }

    @Test
    void aRequestBodyResourceOwnerIdCanNeverExpandAccessBeyondTheVerifiedSubject() {
        // Even a "self-asserted" resourceOwnerId that names some OTHER subject can never grant SELF-scoped
        // access for THAT other subject when evaluated for THIS subject (02-business-invariants #6).
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(
            user.userIdentityId(), "tenant-1", RoleCode.EMPLOYEE, new ResourceScope(ResourceScope.ScopeType.SELF, null), null, null, "admin-1", null, "corr-grant"
        ));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "profile:read", "profile", "p-1", RoleCode.EMPLOYEE,
            new ResourceScope(ResourceScope.ScopeType.SELF, null), "attacker-controlled-owner-id", null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
    }

    /** SPEC-UA-016 (Authentication Context And Assurance Level). */
    @Test
    void noAssuranceRequirementAtAllIsTriviallySatisfied() {
        UserIdentity user = user();

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "ticket:read", "ticket", "t-1", null, null, null, null, null, "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);
    }

    @Test
    void requiresStepUpWhenTheSessionsAcrDoesNotMeetTheRequiredLevel() {
        UserIdentity user = user();
        UserSession session = sessionWithAssurance("urn:mace:acr:0", List.of("pwd"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), session.userSessionId(), "payment:approve", "payment", "p-1",
            null, null, null, "AAL2", List.of(), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.REQUIRE_STEP_UP);
        assertThat(decision.reasonCodes()).isNotEmpty();
        assertThat(decision.assuranceLevel()).isEqualTo("urn:mace:acr:0");
    }

    @Test
    void requiresStepUpWhenTheSessionIsMissingARequiredAmrMethod() {
        UserIdentity user = user();
        UserSession session = sessionWithAssurance("AAL2", List.of("pwd"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), session.userSessionId(), "payment:approve", "payment", "p-1",
            null, null, null, null, List.of("otp"), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.REQUIRE_STEP_UP);
    }

    @Test
    void allowsWhenTheSessionsAssuranceMeetsTheRequirement() {
        UserIdentity user = user();
        UserSession session = sessionWithAssurance("AAL2", List.of("pwd", "otp"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), session.userSessionId(), "payment:approve", "payment", "p-1",
            null, null, null, "AAL2", List.of("otp"), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.ALLOW);
        assertThat(decision.assuranceLevel()).isEqualTo("AAL2");
    }

    @Test
    void deniesWhenAssuranceIsRequiredButNoSessionIsGiven() {
        UserIdentity user = user();

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), null, "payment:approve", "payment", "p-1",
            null, null, null, "AAL2", List.of(), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
    }

    @Test
    void deniesWhenAssuranceIsRequiredButTheGivenSessionIdDoesNotResolve() {
        UserIdentity user = user();

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), "does-not-exist", "payment:approve", "payment", "p-1",
            null, null, null, "AAL2", List.of(), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.DENY);
    }

    @Test
    void assuranceIsCheckedOnTopOfAnAlreadyAllowedRoleScopeAndOwnershipDecision() {
        UserIdentity user = user();
        roleAssignmentService.grant(new GrantRoleAssignmentCommand(user.userIdentityId(), "tenant-1", RoleCode.SUPPORT_AGENT, ResourceScope.tenantWide(), null, null, "admin-1", null, "corr-grant"));
        UserSession session = sessionWithAssurance("urn:mace:acr:0", List.of("pwd"));

        AuthorizationDecision decision = service.evaluate(new EvaluateAuthorizationCommand(
            "tenant-1", "actor-1", user.userIdentityId(), session.userSessionId(), "ticket:read", "ticket", "t-1", RoleCode.SUPPORT_AGENT,
            ResourceScope.tenantWide(), null, "AAL2", List.of(), "corr-1"
        ));

        assertThat(decision.effect()).isEqualTo(DecisionEffect.REQUIRE_STEP_UP);
        assertThat(decision.evaluatedRoles()).containsExactly(RoleCode.SUPPORT_AGENT.name());
    }
}
