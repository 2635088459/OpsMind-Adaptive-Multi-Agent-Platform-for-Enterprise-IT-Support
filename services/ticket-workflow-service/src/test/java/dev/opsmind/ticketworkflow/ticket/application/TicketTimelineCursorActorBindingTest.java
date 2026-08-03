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

/** SPEC-TW-006 §15: a cursor is bound to the issuing actor and cannot be replayed by another. */
@Tag("unit")
class TicketTimelineCursorActorBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");
    private static final String TICKET_ID = UUID.randomUUID().toString();

    private TicketTimelineCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
    }

    private TicketTimelineCursor cursorIssuedTo(String subject) {
        return new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, TICKET_ID, subject, "sha256:scope",
            "EMPLOYEE_PUBLIC_VIEW", TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, NOW,
            NOW, 0, "TICKET_CREATED:x", TicketTimelineSortVersion.CURRENT_VERSION,
            TicketTimelineCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldAcceptWhenSamePrincipalReplaysTheCursor() {
        TicketTimelineCursor cursor = cursorIssuedTo("employee-123");

        assertThatCode(() -> codec.requireMatch(cursor, TICKET_ID, "sha256:scope", "EMPLOYEE_PUBLIC_VIEW", "employee-123"))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenADifferentActorUsesTheCursor() {
        TicketTimelineCursor cursor = cursorIssuedTo("employee-123");

        assertThatThrownBy(() -> codec.requireMatch(cursor, TICKET_ID, "sha256:scope", "EMPLOYEE_PUBLIC_VIEW", "employee-999"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
