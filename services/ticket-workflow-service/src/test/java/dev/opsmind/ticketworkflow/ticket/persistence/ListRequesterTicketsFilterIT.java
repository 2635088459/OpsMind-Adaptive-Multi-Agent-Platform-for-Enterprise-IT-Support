package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-003 §5: status, applicationCode, and creation-range filters are pushed into SQL. */
@Tag("integration")
class ListRequesterTicketsFilterIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldFilterByStatusAndApplicationCode() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        UUID matching = seedTicket(DEFAULT_REQUESTER, "VPN", "NEW", now);
        seedTicket(DEFAULT_REQUESTER, "VPN", "INVESTIGATING", now.minusSeconds(60));
        seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL", "NEW", now.minusSeconds(120));

        ResponseEntity<String> response = listTickets(
            employeeToken(DEFAULT_REQUESTER), Map.of("status", "NEW", "applicationCode", "VPN")
        );

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(matching);
    }

    @Test
    void shouldFilterByCreationRange() {
        Instant beforeRange = Instant.parse("2026-06-30T23:59:59Z");
        Instant withinRange = Instant.parse("2026-07-15T00:00:00Z");
        Instant afterRange = Instant.parse("2026-08-01T00:00:00Z");
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", beforeRange);
        UUID inRange = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", withinRange);
        seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", afterRange);

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of(
            "createdFrom", "2026-07-01T00:00:00Z",
            "createdTo", "2026-08-01T00:00:00Z"
        ));

        assertThat(itemTicketIds(bodyAsJson(response))).containsExactly(inRange);
    }

    @Test
    void appliedFiltersShouldEchoTheRequestedFilters() {
        seedTicket(DEFAULT_REQUESTER, "VPN", "NEW", Instant.parse("2026-07-23T16:30:00Z"));

        ResponseEntity<String> response = listTickets(
            employeeToken(DEFAULT_REQUESTER), Map.of("status", "NEW", "applicationCode", "VPN")
        );

        var appliedFilters = bodyAsJson(response).get("appliedFilters");
        assertThat(appliedFilters.get("status").get(0).asText()).isEqualTo("NEW");
        assertThat(appliedFilters.get("applicationCode").get(0).asText()).isEqualTo("VPN");
    }
}
