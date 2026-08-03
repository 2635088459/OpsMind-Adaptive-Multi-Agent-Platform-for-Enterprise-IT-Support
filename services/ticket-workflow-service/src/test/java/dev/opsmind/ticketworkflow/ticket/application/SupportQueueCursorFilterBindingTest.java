package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueSortVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPEC-TW-005 §14: a cursor issued for one filter set cannot be replayed
 * with different filters — acceptance scenario "Cursor cannot be reused
 * with different filters".
 */
@Tag("unit")
class SupportQueueCursorFilterBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T19:00:00Z");

    private SupportQueueCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        codec = new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));
    }

    private SupportQueueCursor cursorFor(String filterFingerprint, String scopeFingerprint) {
        return new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 0, 0, NOW.minusSeconds(3600), UUID.randomUUID(),
            filterFingerprint, scopeFingerprint, "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(1))
        );
    }

    @Test
    void shouldAcceptWhenEverythingMatches() {
        SupportQueueCursor cursor = cursorFor("sha256:priority-p1", "sha256:scope-a");

        assertThatCode(() -> codec.requireMatch(cursor, "sha256:priority-p1", "sha256:scope-a", "support-100"))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenFilterFingerprintDiffers() {
        SupportQueueCursor cursor = cursorFor("sha256:priority-p1", "sha256:scope-a");

        assertThatThrownBy(() -> codec.requireMatch(cursor, "sha256:priority-p2", "sha256:scope-a", "support-100"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
