package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListCursor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-003 §8: the cursor is tamper-resistant — any payload or signature bit-flip invalidates it. */
@Tag("unit")
class TicketListCursorSignatureTest {

    private static final Instant NOW = Instant.parse("2026-07-25T18:00:00Z");

    private TicketListCursorCodec codecFor(String secret) {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", secret, new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        return new TicketListCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketListCursorSigner(properties));
    }

    private TicketListCursor sampleCursor() {
        return new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, NOW.minusSeconds(3600), UUID.randomUUID(), "sha256:abc",
            TicketListCursor.SORT, "employee-123", TicketListCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void shouldRejectTokenSignedWithADifferentKey() {
        String token = codecFor("secret-a").encode(sampleCursor());

        assertThatThrownBy(() -> codecFor("secret-b").decode(token, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedPayload() {
        TicketListCursorCodec codec = codecFor("secret-a");
        String token = codec.encode(sampleCursor());
        String[] parts = token.split("\\.", 2);

        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        payload[0] ^= 0x01;
        String tamperedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + parts[1];

        assertThatThrownBy(() -> codec.decode(tamperedToken, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedSignature() {
        TicketListCursorCodec codec = codecFor("secret-a");
        String token = codec.encode(sampleCursor());
        String[] parts = token.split("\\.", 2);

        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
        signature[0] ^= 0x01;
        String tamperedToken = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThatThrownBy(() -> codec.decode(tamperedToken, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldAcceptUnmodifiedTokenWithMatchingKey() {
        TicketListCursorCodec codec = codecFor("secret-a");
        TicketListCursor original = sampleCursor();
        String token = codec.encode(original);

        assertThat(codec.decode(token, NOW)).isEqualTo(original);
    }
}
