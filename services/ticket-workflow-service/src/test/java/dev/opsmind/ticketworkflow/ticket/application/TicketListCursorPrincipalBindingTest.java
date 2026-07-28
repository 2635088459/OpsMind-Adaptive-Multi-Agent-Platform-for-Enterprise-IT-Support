package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-TW-003 §8: a cursor is bound to the issuing actor — acceptance
 * scenario "Another employee cannot reuse the cursor".
 */
@Tag("unit")
class TicketListCursorPrincipalBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T18:00:00Z");

    private TicketListCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        codec = new TicketListCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketListCursorSigner(properties));
    }

    private TicketListCursor cursorIssuedTo(String subject) {
        return new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, NOW.minusSeconds(3600), UUID.randomUUID(), "sha256:abc",
            TicketListCursor.SORT, subject, TicketListCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldAcceptWhenSamePrincipalReplaysTheCursor() {
        TicketListCursor cursor = cursorIssuedTo("employee-123");

        assertThatCode(() -> codec.requireMatch(cursor, "sha256:abc", "employee-123")).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenADifferentPrincipalUsesTheCursor() {
        TicketListCursor cursor = cursorIssuedTo("employee-123");

        assertThatThrownBy(() -> codec.requireMatch(cursor, "sha256:abc", "employee-999"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
