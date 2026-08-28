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

    /** SPEC-UA-031 (07-data-model: "Email/display name may be encrypted and erased by retention"). */
    @Test
    void redactPiiNullsUsernameDisplayNameAndEmailOnceDeprovisioned() {
        UserIdentity deprovisioned = link().deprovision(NOW.plusSeconds(1));

        UserIdentity redacted = deprovisioned.redactPii(NOW.plusSeconds(2));

        assertThat(redacted.username()).isNull();
        assertThat(redacted.displayName()).isNull();
        assertThat(redacted.email()).isNull();
        assertThat(redacted.piiRedactedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(redacted.status()).isEqualTo(UserStatus.DEPROVISIONED);
        assertThat(redacted.externalSubject()).isEqualTo(SUBJECT);
        assertThat(redacted.version()).isEqualTo(deprovisioned.version() + 1);
    }

    @Test
    void redactPiiIsIdempotent() {
        UserIdentity redacted = link().deprovision(NOW.plusSeconds(1)).redactPii(NOW.plusSeconds(2));

        UserIdentity redactedAgain = redacted.redactPii(NOW.plusSeconds(3));

        assertThat(redactedAgain).isSameAs(redacted);
    }

    @Test
    void redactPiiRejectsANonDeprovisionedIdentity() {
        UserIdentity active = link();

        assertThatThrownBy(() -> active.redactPii(NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }
}
