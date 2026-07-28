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
 * SPEC-TW-003 §8: a cursor issued for one filter set (or sort) cannot be
 * replayed with different filters — acceptance scenario "Cursor cannot be
 * reused with different filters".
 */
@Tag("unit")
class TicketListCursorFilterBindingTest {

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

    private TicketListCursor cursorFor(String filterFingerprint, String sort, String operation) {
        return new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, NOW.minusSeconds(3600), UUID.randomUUID(), filterFingerprint,
            sort, "employee-123", operation, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldAcceptWhenFingerprintSortAndOperationAllMatch() {
        TicketListCursor cursor = cursorFor("sha256:status-new", TicketListCursor.SORT, TicketListCursor.OPERATION);

        assertThatCode(() -> codec.requireMatch(cursor, "sha256:status-new", "employee-123")).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenFilterFingerprintDiffers() {
        TicketListCursor cursor = cursorFor("sha256:status-new", TicketListCursor.SORT, TicketListCursor.OPERATION);

        assertThatThrownBy(() -> codec.requireMatch(cursor, "sha256:status-resolved", "employee-123"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectWhenSortDiffers() {
        TicketListCursor cursor = cursorFor("sha256:abc", "createdAt:asc,ticketId:asc", TicketListCursor.OPERATION);

        assertThatThrownBy(() -> codec.requireMatch(cursor, "sha256:abc", "employee-123"))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectWhenOperationDiffers() {
        TicketListCursor cursor = cursorFor("sha256:abc", TicketListCursor.SORT, "support_queue_query");

        assertThatThrownBy(() -> codec.requireMatch(cursor, "sha256:abc", "employee-123"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
