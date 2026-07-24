package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ApplicationCodeTest {

    @Test
    void shouldExposeExactlyTheApprovedApplicationCodes() {
        assertThat(ApplicationCode.values())
            .extracting(Enum::name)
            .containsExactlyInAnyOrder("HOUSING_PORTAL", "EMAIL", "VPN", "OTHER");
    }

    @Test
    void shouldParseHousingPortalFromApiWireValue() {
        assertThat(ApplicationCode.valueOf("HOUSING_PORTAL")).isEqualTo(ApplicationCode.HOUSING_PORTAL);
    }
}
