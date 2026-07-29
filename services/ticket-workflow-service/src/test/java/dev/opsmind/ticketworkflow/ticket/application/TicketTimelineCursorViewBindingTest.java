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

/**
 * SPEC-TW-006 §15: a cursor issued for {@code SUPPORT_INTERNAL_VIEW}
 * cannot be reused once the internal Timeline scope is removed and the
 * actor now resolves to {@code SUPPORT_PUBLIC_VIEW} — acceptance
 * scenario "Cursor cannot be reused after the view changes".
 */
@Tag("unit")
class TicketTimelineCursorViewBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");
    private static final String TICKET_ID = UUID.randomUUID().toString();

    private TicketTimelineCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
    }

    private TicketTimelineCursor cursorFor(String viewType) {
        return new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, TICKET_ID, "support-100", "sha256:scope",
            viewType, TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, NOW,
            NOW, 0, "TICKET_CREATED:x", TicketTimelineSortVersion.CURRENT_VERSION,
            TicketTimelineCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldAcceptWhenViewTypeMatches() {
        TicketTimelineCursor cursor = cursorFor("SUPPORT_INTERNAL_VIEW");

        assertThatCode(() -> codec.requireMatch(cursor, TICKET_ID, "sha256:scope", "SUPPORT_INTERNAL_VIEW", "support-100"))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenTheInternalScopeIsRemovedAndTheViewDowngrades() {
        TicketTimelineCursor cursor = cursorFor("SUPPORT_INTERNAL_VIEW");

        assertThatThrownBy(() -> codec.requireMatch(cursor, TICKET_ID, "sha256:scope", "SUPPORT_PUBLIC_VIEW", "support-100"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
