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

/** SPEC-TW-004 §5: INTERNAL_SUPPORT_NOTE requires tickets:message:internal specifically. */
@Tag("integration")
class AddInternalNoteScopeIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldAllowSupportWithInternalScopeToAddAnInternalNote() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", Set.of("tickets:message:internal"), List.of(DEFAULT_APPLICATION_CODE)),
            UUID.randomUUID().toString(),
            supportBody("Escalating to network team, checking VPN concentrator logs.", "INTERNAL_SUPPORT_NOTE")
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void shouldRejectSupportWithOnlyPublicScopeFromAddingAnInternalNote() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", Set.of("tickets:message:public"), List.of(DEFAULT_APPLICATION_CODE)),
            UUID.randomUUID().toString(),
            supportBody("Escalating to network team, checking VPN concentrator logs.", "INTERNAL_SUPPORT_NOTE")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
        assertThat(countRows("ticket.ticket_messages")).isZero();
    }

    @Test
    void shouldRejectPublicMessageScopeSupportOutsideApplicationQueueForInternalNote() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, "INVESTIGATING");

        ResponseEntity<String> response = addMessage(
            ticketId,
            supportToken("support-100", Set.of("tickets:message:internal"), List.of("VPN")),
            UUID.randomUUID().toString(),
            supportBody("Escalating to network team, checking VPN concentrator logs.", "INTERNAL_SUPPORT_NOTE")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countRows("ticket.ticket_messages")).isZero();
    }
}
