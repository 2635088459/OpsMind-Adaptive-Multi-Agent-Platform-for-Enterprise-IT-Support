package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractListRequesterTicketsIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-003 §4: {@code WHERE requester_id = :principalSubject} — ownership enforced in SQL. */
@Tag("integration")
class ListRequesterTicketsOwnershipIT extends AbstractListRequesterTicketsIT {

    @Test
    void shouldReturnOnlyTicketsOwnedByTheAuthenticatedEmployee() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        UUID owned1 = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now);
        UUID owned2 = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(60));
        seedTicket("employee-999", DEFAULT_APPLICATION_CODE, "NEW", now.minusSeconds(30));

        ResponseEntity<String> response = listTickets(employeeToken(DEFAULT_REQUESTER), Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(itemTicketIds(bodyAsJson(response))).containsExactlyInAnyOrder(owned1, owned2);
    }
}
