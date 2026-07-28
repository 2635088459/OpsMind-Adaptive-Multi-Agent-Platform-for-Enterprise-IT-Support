package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueScopeAdapter;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §5/§7: the local policy adapter resolves the trusted scope
 * from raw claim values, silently dropping unknown application codes
 * (BI-007) — mirroring Get Ticket's {@code support_queues} handling —
 * while team ids are opaque strings with no such enum to validate against.
 */
@Tag("unit")
class SupportQueueFilterScopeTest {

    private final SupportQueueScopeAdapter adapter = new SupportQueueScopeAdapter();

    @Test
    void shouldResolveKnownApplicationCodesAndTeams() {
        SupportQueueScope scope = adapter.resolve(List.of("HOUSING_PORTAL", "EMAIL"), List.of("TEAM-HOUSING"));

        assertThat(scope.allowedApplicationCodes()).containsExactlyInAnyOrder(ApplicationCode.HOUSING_PORTAL, ApplicationCode.EMAIL);
        assertThat(scope.allowedTeamIds()).containsExactly("TEAM-HOUSING");
    }

    @Test
    void shouldSilentlyDropUnknownApplicationCodes() {
        SupportQueueScope scope = adapter.resolve(List.of("HOUSING_PORTAL", "SOME_RETIRED_CODE"), List.of());

        assertThat(scope.allowedApplicationCodes()).containsExactly(ApplicationCode.HOUSING_PORTAL);
    }

    @Test
    void shouldResolveEmptyScopeForNullClaims() {
        SupportQueueScope scope = adapter.resolve(null, null);

        assertThat(scope.allowedApplicationCodes()).isEmpty();
        assertThat(scope.allowedTeamIds()).isEmpty();
    }

    @Test
    void fingerprintShouldBeStableRegardlessOfOrderAndDifferWhenScopeDiffers() {
        SupportQueueScope a = adapter.resolve(List.of("HOUSING_PORTAL", "EMAIL"), List.of("TEAM-A", "TEAM-B"));
        SupportQueueScope b = adapter.resolve(List.of("EMAIL", "HOUSING_PORTAL"), List.of("TEAM-B", "TEAM-A"));
        SupportQueueScope narrower = adapter.resolve(List.of("HOUSING_PORTAL"), List.of("TEAM-A", "TEAM-B"));

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.fingerprint()).isNotEqualTo(narrower.fingerprint());
        assertThat(a.fingerprint()).startsWith("sha256:");
    }
}
