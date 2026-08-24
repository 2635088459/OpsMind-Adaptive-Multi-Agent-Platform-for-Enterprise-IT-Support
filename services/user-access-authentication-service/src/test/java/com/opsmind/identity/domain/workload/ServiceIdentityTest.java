package com.opsmind.identity.domain.workload;

import com.opsmind.identity.domain.shared.TenantId;
import com.opsmind.identity.domain.user.ExternalSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceIdentityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ServiceIdentity register(Instant validUntil) {
        return ServiceIdentity.register(
            "svc-1", new TenantId("tenant-1"), new ExternalSubject("https://idp.example", "svc-sub-1"), "client-1",
            "ticket-workflow-service", List.of("identity-api"), List.of("identity:read"), null, validUntil, NOW
        );
    }

    @Test
    void registerStartsActiveAndValid() {
        ServiceIdentity identity = register(null);

        assertThat(identity.status()).isEqualTo(ServiceIdentityStatus.ACTIVE);
        assertThat(identity.isValid(NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void isValidIsFalsePastValidUntil() {
        ServiceIdentity identity = register(NOW.plusSeconds(3600));

        assertThat(identity.isValid(NOW.plusSeconds(3601))).isFalse();
    }

    @Test
    void disableThenRetireReachesTerminal() {
        ServiceIdentity disabled = register(null).disable(NOW.plusSeconds(1));
        assertThat(disabled.status()).isEqualTo(ServiceIdentityStatus.DISABLED);

        ServiceIdentity retired = disabled.retire(NOW.plusSeconds(2));
        assertThat(retired.status()).isEqualTo(ServiceIdentityStatus.RETIRED);
        assertThatThrownBy(() -> retired.retire(NOW.plusSeconds(3)))
            .isInstanceOf(IllegalServiceIdentityTransitionException.class);
    }

    @Test
    void retireIsLegalDirectlyFromActive() {
        ServiceIdentity retired = register(null).retire(NOW.plusSeconds(1));

        assertThat(retired.status()).isEqualTo(ServiceIdentityStatus.RETIRED);
    }
}
