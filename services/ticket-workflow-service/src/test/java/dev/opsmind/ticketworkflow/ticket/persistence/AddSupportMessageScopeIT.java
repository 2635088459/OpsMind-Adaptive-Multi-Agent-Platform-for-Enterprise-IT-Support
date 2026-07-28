package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-004 §5: Support public messages require tickets:message:public plus matching application scope. */
@Tag("integration")
class AddSupportMessageScopeIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldAllowSupportWithinAuthorizedApplicationScope() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", Set.of("tickets:message:public"), List.of(DEFAULT_APPLICATION_CODE)),
            UUID.randomUUID().toString(),
            supportBody("The account has been unlocked. Please try again.", "PUBLIC_SUPPORT_MESSAGE")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldHideTicketFromSupportOutsideAuthorizedApplicationScope() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", Set.of("tickets:message:public"), List.of("VPN")),
            UUID.randomUUID().toString(),
            supportBody("The account has been unlocked. Please try again.", "PUBLIC_SUPPORT_MESSAGE")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countRows("ticket.ticket_messages")).isZero();
    }

    @Test
    void shouldRejectSupportWithoutThePublicMessageScope() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", Set.of(), List.of(DEFAULT_APPLICATION_CODE)),
            UUID.randomUUID().toString(),
            supportBody("The account has been unlocked. Please try again.", "PUBLIC_SUPPORT_MESSAGE")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
    }
}
