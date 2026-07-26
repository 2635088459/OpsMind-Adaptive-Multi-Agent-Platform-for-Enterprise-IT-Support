package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-002 §9/§10: resource-level authorization is pushed into SQL, so a
 * Ticket outside the caller's scope is hidden as {@code 404
 * TICKET_NOT_FOUND} — never a {@code 200} with filtered fields, and never a
 * {@code 403} that would confirm the resource exists.
 */
@Tag("integration")
class GetTicketResourceScopeQueryIT extends AbstractGetTicketIT {

    @Test
    void shouldHideTicketFromEmployeeWhoIsNotTheRequester() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        ResponseEntity<String> response = getTicket(ticketId, employeeToken("employee-999"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
    }

    @Test
    void shouldHideTicketFromSupportOutsideAuthorizedApplicationScope() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        ResponseEntity<String> response = getTicket(ticketId, supportToken("support-100", List.of("VPN")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
        assertThat(response.getBody()).doesNotContain("Cannot sign in to Housing Portal");
    }

    @Test
    void shouldReturnNotFoundForNonExistentTicket() {
        ResponseEntity<String> response = getTicket(UUID.randomUUID(), employeeToken(DEFAULT_REQUESTER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldAllowSupportWhenApplicationCodeIsWithinScope() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        ResponseEntity<String> response = getTicket(ticketId, supportToken("support-100", List.of("VPN", "HOUSING_PORTAL")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
