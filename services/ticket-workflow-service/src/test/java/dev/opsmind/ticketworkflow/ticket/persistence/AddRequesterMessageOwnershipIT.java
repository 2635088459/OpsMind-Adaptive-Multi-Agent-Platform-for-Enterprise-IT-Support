package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-004 §5: an Employee may only message a Ticket they own; ownership is enforced in SQL. */
@Tag("integration")
class AddRequesterMessageOwnershipIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldAllowOwningEmployeeToAddAMessage() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldHideTicketFromAnEmployeeWhoDoesNotOwnIt() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId, employeeToken("employee-999"), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("TICKET_NOT_FOUND");
        assertThat(countRows("ticket.ticket_messages")).isZero();
    }

    @Test
    void shouldReturnNotFoundForNonExistentTicket() {
        ResponseEntity<String> response = addMessage(
            UUID.randomUUID(), employeeToken(DEFAULT_REQUESTER), UUID.randomUUID().toString(), employeeBody(DEFAULT_CONTENT)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
