package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.LogoutCommand;
import com.opsmind.identity.application.command.RefreshSessionCommand;
import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.ExternalSubject;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.support.FakeOidcProviderPort;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import com.opsmind.identity.support.InMemoryUserSessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ManageSessionServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final FakeOidcProviderPort oidcProviderPort = new FakeOidcProviderPort();
    private final com.opsmind.identity.support.InMemoryIdentityMetricsPort identityMetricsPort = new com.opsmind.identity.support.InMemoryIdentityMetricsPort();
    private final ManageSessionService service = new ManageSessionService(new InMemoryUserSessionRepository(), userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), oidcProviderPort, identityMetricsPort, clock);

    private StartSessionCommand startCommand() {
        return new StartSessionCommand("tenant-1", "https://idp.example", "sub-1", "idp-hash", "token-hash", "client-1", "urn:mace:acr:0", List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-1");
    }

    @Test
    void startCreatesAnActiveSessionForAnActiveUser() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));

        UserSession session = service.start(startCommand());

        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.isValid(clock.now().plusSeconds(1))).isTrue();
        assertThat(identityMetricsPort.sessionLifecycleEvents()).containsExactly("STARTED");
    }

    @Test
    void startDeniesADisabledUser() {
        UserIdentity user = provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        provisionUserService.changeStatus(new ChangeUserIdentityStatusCommand(user.userIdentityId(), UserStatus.DISABLED, "policy", "corr-disable"));

        assertThatThrownBy(() -> service.start(startCommand())).isInstanceOf(UserIdentityNotEligibleException.class);
    }

    /** SPEC-UA-034 (07-data-model §user_sessions: "UNIQUE session hash"; 11-security: "token substitution/replay/theft"). */
    @Test
    void startRejectsATokenAlreadyBackingAnotherSession() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        service.start(startCommand());

        assertThatThrownBy(() -> service.start(startCommand())).isInstanceOf(com.opsmind.identity.application.exception.TokenReplayDetectedException.class);
    }

    @Test
    void revokeIsIdempotent() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());

        UserSession firstRevoke = service.revoke(new RevokeSessionCommand(session.userSessionId(), "admin-1", "logout", "corr-2"));
        UserSession secondRevoke = service.revoke(new RevokeSessionCommand(session.userSessionId(), "admin-1", "logout again", "corr-3"));

        assertThat(firstRevoke.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(secondRevoke.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(secondRevoke.revocationReason()).isEqualTo("logout");
        // The idempotent-replay branch returns early before ever recording a metric — only the real transition counts.
        assertThat(identityMetricsPort.sessionLifecycleEvents()).containsExactly("STARTED", "REVOKED");
    }

    @Test
    void findByIdThrowsWhenMissing() {
        assertThatThrownBy(() -> service.findById("missing")).isInstanceOf(UserSessionNotFoundException.class);
    }

    @Test
    void reconcileExpiresActiveSessionsPastTheirOwnExpiry() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());

        assertThat(service.reconcileExpired()).isZero();

        clock.advanceTo(session.expiresAt());
        assertThat(service.reconcileExpired()).isEqualTo(1);
        assertThat(service.findById(session.userSessionId()).status()).isEqualTo(SessionStatus.EXPIRED);
    }

    @Test
    void reconcileNeverTouchesAnAlreadyRevokedSession() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());
        service.revoke(new RevokeSessionCommand(session.userSessionId(), "admin-1", "logout", "corr-2"));

        clock.advanceTo(session.expiresAt());
        assertThat(service.reconcileExpired()).isZero();
        assertThat(service.findById(session.userSessionId()).status()).isEqualTo(SessionStatus.REVOKED);
    }

    @Test
    void refreshExtendsLastSeenAtWithoutTouchingStatusOrExpiry() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());
        Instant originalExpiry = session.expiresAt();

        clock.advanceTo(clock.now().plusSeconds(60));
        UserSession refreshed = service.refresh(new RefreshSessionCommand(session.userSessionId(), "corr-refresh"));

        assertThat(refreshed.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(refreshed.expiresAt()).isEqualTo(originalExpiry);
        assertThat(refreshed.lastSeenAt()).isEqualTo(clock.now());
    }

    @Test
    void refreshThrowsWhenTheSessionIsMissing() {
        assertThatThrownBy(() -> service.refresh(new RefreshSessionCommand("missing", "corr-refresh")))
            .isInstanceOf(UserSessionNotFoundException.class);
    }

    @Test
    void logoutRevokesTheCallersOwnActiveSessionDerivedFromItsIdpSessionHash() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());

        service.logout(new LogoutCommand("tenant-1", "https://idp.example", "sub-1", "idp-hash", "corr-logout"));

        assertThat(service.findById(session.userSessionId()).status()).isEqualTo(SessionStatus.REVOKED);
    }

    @Test
    void logoutSilentlyNoOpsWhenNoActiveSessionMatchesTheIdpSessionHash() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        service.start(startCommand());

        service.logout(new LogoutCommand("tenant-1", "https://idp.example", "sub-1", "does-not-exist", "corr-logout"));
        // no exception; nothing to assert beyond "did not throw" since logout never discloses existence
    }

    @Test
    void logoutNeverRevokesASessionBelongingToADifferentSubjectEvenIfTheHashSomehowCollided() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());

        service.logout(new LogoutCommand("tenant-1", "https://idp.example", "sub-2", "idp-hash", "corr-logout"));

        assertThat(service.findById(session.userSessionId()).status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void reconcileEndSessionNotificationsCallsTheIdpForEveryUnnotifiedRevokedSessionAndMarksItNotified() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());
        service.revoke(new RevokeSessionCommand(session.userSessionId(), "admin-1", "logout", "corr-2"));

        int notified = service.reconcileEndSessionNotifications();

        assertThat(notified).isEqualTo(1);
        assertThat(oidcProviderPort.endSessionRequests()).containsExactly(new ExternalSubject("https://idp.example", "sub-1"));
        assertThat(service.reconcileEndSessionNotifications()).isZero();
    }

    @Test
    void reconcileEndSessionNotificationsLeavesASessionUnmarkedWhenTheIdpCallFailsSoItIsRetriedLater() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());
        service.revoke(new RevokeSessionCommand(session.userSessionId(), "admin-1", "logout", "corr-2"));
        oidcProviderPort.failNextRequestsWith(new RuntimeException("IdP unreachable"));

        assertThat(service.reconcileEndSessionNotifications()).isZero();

        oidcProviderPort.failNextRequestsWith(null);
        assertThat(service.reconcileEndSessionNotifications()).isEqualTo(1);
    }

    /** SPEC-UA-033 (10-failure-handling: "Delayed revocation event | ... | Reconciliation scan"). */
    @Test
    void reconcileForInactiveIdentitiesRevokesAnActiveSessionOfADisabledUser() {
        UserIdentity user = provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());
        provisionUserService.changeStatus(new ChangeUserIdentityStatusCommand(user.userIdentityId(), UserStatus.DISABLED, "policy", "corr-disable"));

        int revoked = service.reconcileForInactiveIdentities();

        assertThat(revoked).isEqualTo(1);
        UserSession found = service.findById(session.userSessionId());
        assertThat(found.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(found.revokedBy()).isEqualTo("system:reconciliation");
        assertThat(identityMetricsPort.sessionLifecycleEvents()).contains("REVOKED");
    }

    @Test
    void reconcileForInactiveIdentitiesRevokesAnActiveSessionOfADeprovisionedUser() {
        UserIdentity user = provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());
        provisionUserService.changeStatus(new ChangeUserIdentityStatusCommand(user.userIdentityId(), UserStatus.DEPROVISIONED, "offboarded", "corr-deprovision"));

        assertThat(service.reconcileForInactiveIdentities()).isEqualTo(1);
        assertThat(service.findById(session.userSessionId()).status()).isEqualTo(SessionStatus.REVOKED);
    }

    @Test
    void reconcileForInactiveIdentitiesLeavesAnActiveUsersSessionAlone() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        UserSession session = service.start(startCommand());

        assertThat(service.reconcileForInactiveIdentities()).isZero();
        assertThat(service.findById(session.userSessionId()).status()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void reconcileForInactiveIdentitiesIsIdempotentAcrossRuns() {
        UserIdentity user = provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        service.start(startCommand());
        provisionUserService.changeStatus(new ChangeUserIdentityStatusCommand(user.userIdentityId(), UserStatus.DISABLED, "policy", "corr-disable"));

        assertThat(service.reconcileForInactiveIdentities()).isEqualTo(1);
        assertThat(service.reconcileForInactiveIdentities()).isZero();
    }
}
