package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractSupportQueueIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-005 §1: a pure query emits no Outbox event and creates no audit row per §21's optional policy hook being unimplemented. */
@Tag("integration")
class SupportQueueDoesNotCreateOutboxIT extends AbstractSupportQueueIT {

    @Test
    void shouldNotCreateOutboxOrAuditRowsWhenQueryingTheQueue() {
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        seedTicket(DEFAULT_APPLICATION_CODE, "NEW", now);

        ResponseEntity<String> response = queryQueue(
            supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), Set.of(DEFAULT_TEAM)), Map.of()
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(countRows("ticket.outbox_events")).isZero();
        assertThat(countRows("ticket.audit_records")).isZero();
    }
}
