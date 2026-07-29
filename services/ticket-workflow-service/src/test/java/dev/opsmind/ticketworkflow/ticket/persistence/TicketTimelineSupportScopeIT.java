package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-006 §6: Support Timeline authorization is resolved from the {@code support_queues} JWT claim, never a remote policy call. */
@Tag("integration")
class TicketTimelineSupportScopeIT extends AbstractTicketTimelineIT {

    @Test
    void supportInsideAllowedApplicationScopeShouldSeeTheTimeline() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyAsJson(response).get("viewType").asText()).isEqualTo("SUPPORT_PUBLIC_VIEW");
    }

    @Test
    void supportOutsideAllowedApplicationScopeShouldReceiveNotFound() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of("VPN")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bodyAsJson(response).get("error").get("code").asText()).isEqualTo("TICKET_NOT_FOUND");
    }

    @Test
    void supportWithInternalScopeShouldResolveInternalView() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyAsJson(response).get("viewType").asText()).isEqualTo("SUPPORT_INTERNAL_VIEW");
    }

    @Test
    void supportMissingCoarseScopeShouldReceiveForbidden() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE);
        String tokenWithoutQueueScope = dev.opsmind.ticketworkflow.support.TestJwtSupport.mintToken(
            "support-100", "support-console", Set.of(),
            java.util.Map.of("actor_type", "IT_SUPPORT", "support_queues", java.util.List.of(DEFAULT_APPLICATION_CODE))
        );

        ResponseEntity<String> response = getTimeline(ticketId, tokenWithoutQueueScope);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyAsJson(response).get("error").get("code").asText()).isEqualTo("FORBIDDEN");
    }
}
