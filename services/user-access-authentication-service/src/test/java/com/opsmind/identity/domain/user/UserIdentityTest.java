package com.opsmind.identity.domain.user;

import com.opsmind.identity.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserIdentityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final ExternalSubject SUBJECT = new ExternalSubject("https://idp.example/realms/opsmind", "sub-1");

    private UserIdentity link() {
        return UserIdentity.link("u-1", TENANT, SUBJECT, "alice", "Alice", "alice@example.com", IdentityType.HUMAN, NOW);
    }

    @Test
    void linkStartsActive() {
        UserIdentity identity = link();

        assertThat(identity.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(identity.isActive()).isTrue();
        assertThat(identity.externalSubject()).isEqualTo(SUBJECT);
    }

    @Test
    void disableThenEnableRoundTrips() {
        UserIdentity disabled = link().disable(NOW.plusSeconds(1));
        assertThat(disabled.status()).isEqualTo(UserStatus.DISABLED);

        UserIdentity enabled = disabled.enable(NOW.plusSeconds(2));
        assertThat(enabled.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void deprovisionIsTerminal() {
        UserIdentity deprovisioned = link().deprovision(NOW.plusSeconds(1));

        assertThatThrownBy(() -> deprovisioned.enable(NOW.plusSeconds(2)))
            .isInstanceOf(IllegalUserIdentityTransitionException.class);
        assertThatThrownBy(() -> deprovisioned.deprovision(NOW.plusSeconds(2)))
            .isInstanceOf(IllegalUserIdentityTransitionException.class);
    }

    @Test
    void deprovisionIsLegalFromDisabledToo() {
        UserIdentity disabled = link().disable(NOW.plusSeconds(1));

        UserIdentity deprovisioned = disabled.deprovision(NOW.plusSeconds(2));
        assertThat(deprovisioned.status()).isEqualTo(UserStatus.DEPROVISIONED);
    }

    @Test
    void syncIgnoresAStaleProfileVersion() {
        UserIdentity synced = link().sync("alice2", "Alice Two", "alice2@example.com", 5, NOW.plusSeconds(10));
        assertThat(synced.profileVersion()).isEqualTo(5);

        UserIdentity staleSync = synced.sync("alice-stale", "Stale", "stale@example.com", 3, NOW.plusSeconds(20));
        assertThat(staleSync.username()).isEqualTo("alice2");
        assertThat(staleSync.profileVersion()).isEqualTo(5);
    }
}
