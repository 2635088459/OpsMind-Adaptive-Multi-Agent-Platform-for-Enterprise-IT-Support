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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-005 §14: cursor is opaque, versioned, and round-trips exactly through encode/decode. */
@Tag("unit")
class SupportQueueCursorCodecTest {

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

    private SupportQueueCursor sampleCursor() {
        return new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 1, 2, NOW.minusSeconds(3600), UUID.randomUUID(),
            "sha256:filters", "sha256:scope", "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(1))
        );
    }

    @Test
    void shouldRoundTripEncodeAndDecode() {
        SupportQueueCursor original = sampleCursor();

        String token = codec.encode(original);
        SupportQueueCursor decoded = codec.decode(token, NOW);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void shouldProduceOpaqueTwoPartToken() {
        String token = codec.encode(sampleCursor());

        assertThat(token.split("\\.", -1)).hasSize(2);
        assertThat(token).doesNotContain("support-100");
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
        SupportQueueCursor wrongVersion = new SupportQueueCursor(
            99, NOW, 1, 2, NOW, UUID.randomUUID(), "sha256:filters", "sha256:scope", "support-100",
            SupportQueueSortVersion.CURRENT_VERSION, SupportQueueCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(1))
        );
        String token = codec.encode(wrongVersion);

        assertThatThrownBy(() -> codec.decode(token, NOW)).isInstanceOf(InvalidCursorException.class);
    }
}
