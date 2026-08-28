package com.opsmind.identity.domain.breakglass;

import com.opsmind.identity.domain.role.ResourceScope;
import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakGlassGrantTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private BreakGlassGrant activate(Instant expiresAt) {
        return BreakGlassGrant.activate(
            "bg-1", new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "admin-1"), ResourceScope.tenantWide(),
            "approval-ref-1", "production incident", "admin-1", NOW, expiresAt, "corr-1"
        );
    }

    @Test
    void activateStartsActive() {
        BreakGlassGrant grant = activate(NOW.plusSeconds(3600));

        assertThat(grant.status()).isEqualTo(BreakGlassStatus.ACTIVE);
        assertThat(grant.isValid(NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void expiresAtMustBeAfterGrantedAt() {
        assertThatThrownBy(() -> BreakGlassGrant.activate(
            "bg-1", new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "admin-1"), ResourceScope.tenantWide(),
            "approval-ref-1", "production incident", "admin-1", NOW, NOW, "corr-1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalReferenceMustNotBeBlank() {
        assertThatThrownBy(() -> BreakGlassGrant.activate(
            "bg-1", new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "admin-1"), ResourceScope.tenantWide(),
            " ", "production incident", "admin-1", NOW, NOW.plusSeconds(60), "corr-1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonMustNotBeBlank() {
        assertThatThrownBy(() -> BreakGlassGrant.activate(
            "bg-1", new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "admin-1"), ResourceScope.tenantWide(),
            "approval-ref-1", "", "admin-1", NOW, NOW.plusSeconds(60), "corr-1"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expireIsLegalOnlyFromActive() {
        BreakGlassGrant expired = activate(NOW.plusSeconds(3600)).expire(NOW.plusSeconds(3600));

        assertThat(expired.status()).isEqualTo(BreakGlassStatus.EXPIRED);
        assertThat(expired.isValid(NOW.plusSeconds(3601))).isFalse();
        assertThatThrownBy(() -> expired.expire(NOW.plusSeconds(3700))).isInstanceOf(IllegalBreakGlassTransitionException.class);
    }

    @Test
    void revokeEndsAnActiveGrantEarly() {
        BreakGlassGrant grant = activate(NOW.plusSeconds(3600));

        BreakGlassGrant revoked = grant.revoke("security-admin", "misuse suspected", NOW.plusSeconds(30));

        assertThat(revoked.status()).isEqualTo(BreakGlassStatus.REVOKED);
        assertThat(revoked.revokedBy()).isEqualTo("security-admin");
        assertThat(revoked.isValid(NOW.plusSeconds(31))).isFalse();
    }

    @Test
    void revokeIsIllegalOnceAlreadyExpired() {
        BreakGlassGrant expired = activate(NOW.plusSeconds(3600)).expire(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> expired.revoke("admin-2", "too late", NOW.plusSeconds(3700)))
            .isInstanceOf(IllegalBreakGlassTransitionException.class);
    }
}
