package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ChangeUserIdentityStatusCommand;
import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.infrastructure.audit.InMemoryAuditPort;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserIdentityRepository;
import com.opsmind.identity.infrastructure.persistence.adapter.InMemoryUserSessionRepository;
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
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), clock);
    private final ManageSessionService service = new ManageSessionService(new InMemoryUserSessionRepository(), userIdentityRepository, new InMemoryAuditPort(), clock);

    private StartSessionCommand startCommand() {
        return new StartSessionCommand("tenant-1", "https://idp.example", "sub-1", "idp-hash", "token-hash", "client-1", "urn:mace:acr:0", List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-1");
    }

    @Test
    void startCreatesAnActiveSessionForAnActiveUser() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));

        UserSession session = service.start(startCommand());

        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.isValid(clock.now().plusSeconds(1))).isTrue();
    }

    @Test
    void startDeniesADisabledUser() {
        UserIdentity user = provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-setup"));
        provisionUserService.changeStatus(new ChangeUserIdentityStatusCommand(user.userIdentityId(), UserStatus.DISABLED, "policy", "corr-disable"));

        assertThatThrownBy(() -> service.start(startCommand())).isInstanceOf(UserIdentityNotEligibleException.class);
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
    }

    @Test
    void findByIdThrowsWhenMissing() {
        assertThatThrownBy(() -> service.findById("missing")).isInstanceOf(UserSessionNotFoundException.class);
    }
}
