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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-006 §15: cursor is opaque, versioned, and round-trips exactly through encode/decode. */
@Tag("unit")
class TicketTimelineCursorCodecTest {

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

    private TicketTimelineCursor sampleCursor() {
        return new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, UUID.randomUUID().toString(), "employee-123", "sha256:scope",
            "EMPLOYEE_PUBLIC_VIEW", TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, NOW,
            NOW.minusSeconds(3600), 2, "MESSAGE:" + UUID.randomUUID(), TicketTimelineSortVersion.CURRENT_VERSION,
            TicketTimelineCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldRoundTripEncodeAndDecode() {
        TicketTimelineCursor original = sampleCursor();

        String token = codec.encode(original);
        TicketTimelineCursor decoded = codec.decode(token, NOW);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void shouldProduceOpaqueTwoPartToken() {
        String token = codec.encode(sampleCursor());

        assertThat(token.split("\\.", -1)).hasSize(2);
        assertThat(token).doesNotContain("employee-123");
    }

    @Test
    void shouldRejectNullOrBlankToken() {
        assertThatThrownBy(() -> codec.decode(null, NOW)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode("", NOW)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode("   ", NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectMalformedToken() {
        assertThatThrownBy(() -> codec.decode("not-a-valid-cursor", NOW)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode("only.one.dot.extra", NOW)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.decode("!!!not-base64!!!.!!!not-base64!!!", NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectUnsupportedVersion() {
        TicketTimelineCursor wrongVersion = new TicketTimelineCursor(
            99, UUID.randomUUID().toString(), "employee-123", "sha256:scope", "EMPLOYEE_PUBLIC_VIEW",
            TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, NOW, NOW, 0, "TICKET_CREATED:x",
            TicketTimelineSortVersion.CURRENT_VERSION, TicketTimelineCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
        String token = codec.encode(wrongVersion);

        assertThatThrownBy(() -> codec.decode(token, NOW)).isInstanceOf(InvalidCursorException.class);
    }
}
