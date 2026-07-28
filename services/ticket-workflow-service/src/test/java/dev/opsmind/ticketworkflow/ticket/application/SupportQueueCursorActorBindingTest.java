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

/** SPEC-TW-005 §14: a cursor is bound to the issuing actor and cannot be replayed by another. */
@Tag("unit")
class SupportQueueCursorActorBindingTest {

    private static final Instant NOW = Instant.parse("2026-07-25T19:00:00Z");

    private SupportQueueCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        codec = new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));
    }

    private SupportQueueCursor cursorIssuedTo(String subject) {
        return new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 0, 0, NOW.minusSeconds(3600), UUID.randomUUID(),
            "sha256:filters", "sha256:scope", subject, SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(1))
        );
    }

    @Test
    void shouldAcceptWhenSamePrincipalReplaysTheCursor() {
        SupportQueueCursor cursor = cursorIssuedTo("support-100");

        assertThatCode(() -> codec.requireMatch(cursor, "sha256:filters", "sha256:scope", "support-100"))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenADifferentActorUsesTheCursor() {
        SupportQueueCursor cursor = cursorIssuedTo("support-100");

        assertThatThrownBy(() -> codec.requireMatch(cursor, "sha256:filters", "sha256:scope", "support-999"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
