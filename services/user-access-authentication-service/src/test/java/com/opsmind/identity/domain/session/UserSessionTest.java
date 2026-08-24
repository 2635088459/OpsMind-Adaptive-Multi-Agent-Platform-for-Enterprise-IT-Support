package com.opsmind.identity.domain.session;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserSessionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ExternalSubject SUBJECT = new ExternalSubject("https://idp.example", "sub-1");

    private UserSession start() {
        AuthenticationAssurance assurance = new AuthenticationAssurance("urn:mace:acr:0", List.of("pwd"), NOW);
        return UserSession.start("s-1", new TenantId("tenant-1"), SUBJECT, "idp-hash", "token-hash", "client-1", assurance, "device-hash", NOW, NOW.plusSeconds(3600));
    }

    @Test
    void startIsActiveAndValid() {
        UserSession session = start();

        assertThat(session.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(session.isValid(NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void isValidIsFalseOncePastExpiry() {
        UserSession session = start();

        assertThat(session.isValid(NOW.plusSeconds(3601))).isFalse();
    }

    @Test
    void everyNonActiveStateIsFinal() {
        UserSession revoked = start().revoke("admin-1", "logout", NOW.plusSeconds(10));

        assertThatThrownBy(() -> revoked.revoke("admin-1", "again", NOW.plusSeconds(20)))
            .isInstanceOf(IllegalUserSessionTransitionException.class);
        assertThatThrownBy(() -> revoked.expire(NOW.plusSeconds(20)))
            .isInstanceOf(IllegalUserSessionTransitionException.class);
        assertThatThrownBy(() -> revoked.terminate(NOW.plusSeconds(20)))
            .isInstanceOf(IllegalUserSessionTransitionException.class);
    }

    @Test
    void markCompromisedIsLegalFromActive() {
        UserSession compromised = start().markCompromised("token substitution detected", NOW.plusSeconds(5));

        assertThat(compromised.status()).isEqualTo(SessionStatus.COMPROMISED);
        assertThat(compromised.isValid(NOW.plusSeconds(6))).isFalse();
    }

    @Test
    void touchUpdatesLastSeenAtWithoutChangingStatus() {
        UserSession touched = start().touch(NOW.plusSeconds(30));

        assertThat(touched.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(touched.lastSeenAt()).isEqualTo(NOW.plusSeconds(30));
    }
}
