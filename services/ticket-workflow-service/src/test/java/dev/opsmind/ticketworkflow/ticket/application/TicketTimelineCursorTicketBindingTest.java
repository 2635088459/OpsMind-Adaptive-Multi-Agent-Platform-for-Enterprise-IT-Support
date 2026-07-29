package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-006 §15: a cursor issued for one Ticket cannot be replayed against another. */
@Tag("unit")
class TicketTimelineCursorTicketBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");

    private TicketTimelineCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
    }

    private TicketTimelineCursor cursorFor(String ticketId) {
        return new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, ticketId, "employee-123", "sha256:scope",
            "EMPLOYEE_PUBLIC_VIEW", TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, NOW,
            NOW, 0, "TICKET_CREATED:x", TicketTimelineSortVersion.CURRENT_VERSION,
            TicketTimelineCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldAcceptWhenTicketIdMatches() {
        String ticketId = UUID.randomUUID().toString();
        TicketTimelineCursor cursor = cursorFor(ticketId);

        assertThatCode(() -> codec.requireMatch(cursor, ticketId, "sha256:scope", "EMPLOYEE_PUBLIC_VIEW", "employee-123"))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenUsedForADifferentTicket() {
        TicketTimelineCursor cursor = cursorFor(UUID.randomUUID().toString());

        assertThatThrownBy(() -> codec.requireMatch(cursor, UUID.randomUUID().toString(), "sha256:scope", "EMPLOYEE_PUBLIC_VIEW", "employee-123"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
