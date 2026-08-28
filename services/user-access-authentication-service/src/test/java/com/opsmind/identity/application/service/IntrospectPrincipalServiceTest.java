package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.LinkUserIdentityCommand;
import com.opsmind.identity.application.command.RevokeSessionCommand;
import com.opsmind.identity.application.command.StartSessionCommand;
import com.opsmind.identity.application.dto.PrincipalContextView;
import com.opsmind.identity.application.query.IntrospectPrincipalContextQuery;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.user.IdentityType;
import com.opsmind.identity.domain.user.UserIdentity;
import com.opsmind.identity.domain.user.UserStatus;
import com.opsmind.identity.support.FakeEventPublisherPort;
import com.opsmind.identity.support.InMemoryAuditPort;
import com.opsmind.identity.support.InMemoryUserIdentityRepository;
import com.opsmind.identity.support.InMemoryUserSessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-UA-007 (05-api-contracts {@code POST /tokens/introspect-context}). */
@Tag("unit")
class IntrospectPrincipalServiceTest {

    private final FixedClockPort clock = new FixedClockPort(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryUserIdentityRepository userIdentityRepository = new InMemoryUserIdentityRepository();
    private final InMemoryUserSessionRepository userSessionRepository = new InMemoryUserSessionRepository();
    private final ProvisionUserService provisionUserService = new ProvisionUserService(userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), clock);
    private final ManageSessionService manageSessionService = new ManageSessionService(userSessionRepository, userIdentityRepository, new InMemoryAuditPort(), new FakeEventPublisherPort(), new com.opsmind.identity.support.FakeOidcProviderPort(), new com.opsmind.identity.support.InMemoryIdentityMetricsPort(), clock);
    private final IntrospectPrincipalService service = new IntrospectPrincipalService(userIdentityRepository, userSessionRepository);

    private IntrospectPrincipalContextQuery query(String userSessionId) {
        return new IntrospectPrincipalContextQuery("tenant-1", "https://idp.example", "sub-1", "urn:mace:acr:0", List.of("pwd"), clock.now(), userSessionId);
    }

    @Test
    void introspectsAnUnlinkedSubjectWithNullIdentityFieldsAndNeverProvisionsIt() {
        PrincipalContextView view = service.introspect(query(null));

        assertThat(view.tenantId()).isEqualTo("tenant-1");
        assertThat(view.issuer()).isEqualTo("https://idp.example");
        assertThat(view.subject()).isEqualTo("sub-1");
        assertThat(view.userIdentityId()).isNull();
        assertThat(view.identityStatus()).isNull();
        assertThat(view.acr()).isEqualTo("urn:mace:acr:0");
        assertThat(view.amr()).containsExactly("pwd");
        assertThat(userIdentityRepository.findByExternalSubject("tenant-1", new com.opsmind.identity.domain.user.ExternalSubject("https://idp.example", "sub-1"))).isEmpty();
    }

    @Test
    void introspectsALinkedIdentityWithoutASessionId() {
        UserIdentity linked = provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-1"));

        PrincipalContextView view = service.introspect(query(null));

        assertThat(view.userIdentityId()).isEqualTo(linked.userIdentityId());
        assertThat(view.identityStatus()).isEqualTo(UserStatus.ACTIVE.name());
        assertThat(view.userSessionId()).isNull();
        assertThat(view.sessionStatus()).isNull();
    }

    @Test
    void includesTheOwnSessionsStatusWhenTheSessionBelongsToTheSameSubject() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-1"));
        UserSession session = manageSessionService.start(new StartSessionCommand(
            "tenant-1", "https://idp.example", "sub-1", "idp-hash", "token-hash", "client-1", "urn:mace:acr:0",
            List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-2"
        ));

        PrincipalContextView view = service.introspect(query(session.userSessionId()));

        assertThat(view.userSessionId()).isEqualTo(session.userSessionId());
        assertThat(view.sessionStatus()).isEqualTo(SessionStatus.ACTIVE.name());
    }

    @Test
    void revokedSessionStatusIsReflected() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-1"));
        UserSession session = manageSessionService.start(new StartSessionCommand(
            "tenant-1", "https://idp.example", "sub-1", "idp-hash", "token-hash", "client-1", "urn:mace:acr:0",
            List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-2"
        ));
        manageSessionService.revoke(new RevokeSessionCommand(session.userSessionId(), "admin-1", "logout", "corr-3"));

        PrincipalContextView view = service.introspect(query(session.userSessionId()));

        assertThat(view.sessionStatus()).isEqualTo(SessionStatus.REVOKED.name());
    }

    @Test
    void neverDisclosesASessionThatBelongsToADifferentSubject() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-1"));
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-2", "bob", "Bob", null, IdentityType.HUMAN, "corr-2"));
        UserSession bobSession = manageSessionService.start(new StartSessionCommand(
            "tenant-1", "https://idp.example", "sub-2", "idp-hash", "token-hash", "client-1", "urn:mace:acr:0",
            List.of("pwd"), clock.now(), "device-hash", Duration.ofHours(1), "corr-3"
        ));

        // sub-1's own introspection call names sub-2's session id.
        PrincipalContextView view = service.introspect(query(bobSession.userSessionId()));

        assertThat(view.userSessionId()).isNull();
        assertThat(view.sessionStatus()).isNull();
    }

    @Test
    void anUnknownSessionIdIsTreatedTheSameAsOneBelongingToSomeoneElse() {
        provisionUserService.link(new LinkUserIdentityCommand("tenant-1", "https://idp.example", "sub-1", "alice", "Alice", null, IdentityType.HUMAN, "corr-1"));

        PrincipalContextView view = service.introspect(query("does-not-exist"));

        assertThat(view.userSessionId()).isNull();
        assertThat(view.sessionStatus()).isNull();
    }
}
