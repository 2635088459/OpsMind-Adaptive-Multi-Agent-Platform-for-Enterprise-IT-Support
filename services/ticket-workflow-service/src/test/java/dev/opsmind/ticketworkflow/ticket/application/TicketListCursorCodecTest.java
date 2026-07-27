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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-003 §8: cursor is opaque, versioned, and round-trips exactly through encode/decode. */
@Tag("unit")
class TicketListCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-07-25T18:00:00Z");

    private TicketListCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24))
        );
        codec = new TicketListCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketListCursorSigner(properties));
    }

    private TicketListCursor sampleCursor() {
        return new TicketListCursor(
            TicketListCursor.CURRENT_VERSION,
            NOW.minusSeconds(3600),
            UUID.randomUUID(),
            "sha256:abc",
            TicketListCursor.SORT,
            "employee-123",
            TicketListCursor.OPERATION,
            NOW,
            NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldRoundTripEncodeAndDecode() {
        TicketListCursor original = sampleCursor();

        String token = codec.encode(original);
        TicketListCursor decoded = codec.decode(token, NOW);

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
        TicketListCursor wrongVersion = new TicketListCursor(
            99, NOW, UUID.randomUUID(), "sha256:abc", TicketListCursor.SORT, "employee-123",
            TicketListCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
        String token = codec.encode(wrongVersion);

        assertThatThrownBy(() -> codec.decode(token, NOW)).isInstanceOf(InvalidCursorException.class);
    }
}
