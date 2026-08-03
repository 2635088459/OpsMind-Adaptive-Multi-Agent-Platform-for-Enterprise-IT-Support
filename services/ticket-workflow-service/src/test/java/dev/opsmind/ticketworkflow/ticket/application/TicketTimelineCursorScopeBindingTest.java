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

/** SPEC-TW-006 §15: a cursor cannot be reused after the actor's authorization scope changes. */
@Tag("unit")
class TicketTimelineCursorScopeBindingTest {

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

    private TicketTimelineCursor cursorFor(String scopeFingerprint) {
        return new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, TICKET_ID, "support-100", scopeFingerprint,
            "SUPPORT_PUBLIC_VIEW", TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, NOW,
            NOW, 0, "TICKET_CREATED:x", TicketTimelineSortVersion.CURRENT_VERSION,
            TicketTimelineCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldAcceptWhenScopeFingerprintMatches() {
        TicketTimelineCursor cursor = cursorFor("sha256:scope-before-change");

        assertThatCode(() -> codec.requireMatch(cursor, TICKET_ID, "sha256:scope-before-change", "SUPPORT_PUBLIC_VIEW", "support-100"))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhenScopeFingerprintDiffersAfterAPermissionChange() {
        TicketTimelineCursor cursor = cursorFor("sha256:scope-before-change");

        assertThatThrownBy(() -> codec.requireMatch(cursor, TICKET_ID, "sha256:scope-after-change", "SUPPORT_PUBLIC_VIEW", "support-100"))
            .isInstanceOf(InvalidCursorException.class);
    }
}
