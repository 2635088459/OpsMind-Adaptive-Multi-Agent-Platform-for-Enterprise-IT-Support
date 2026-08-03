package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueSortVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-005 §14: the cursor is tamper-resistant — any payload or signature bit-flip invalidates it. */
@Tag("unit")
class SupportQueueCursorSignatureTest {

    private static final Instant NOW = Instant.parse("2026-07-25T19:00:00Z");

    private SupportQueueCursorCodec codecFor(String secret) {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", secret, new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        return new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));
    }

    private SupportQueueCursor sampleCursor() {
        return new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 1, 2, NOW.minusSeconds(3600), UUID.randomUUID(),
            "sha256:filters", "sha256:scope", "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW, NOW.plus(Duration.ofHours(1))
        );
    }

    @Test
    void shouldRejectTokenSignedWithADifferentKey() {
        String token = codecFor("secret-a").encode(sampleCursor());

        assertThatThrownBy(() -> codecFor("secret-b").decode(token, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedPayload() {
        SupportQueueCursorCodec codec = codecFor("secret-a");
        String token = codec.encode(sampleCursor());
        String[] parts = token.split("\\.", 2);

        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        payload[0] ^= 0x01;
        String tamperedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + parts[1];

        assertThatThrownBy(() -> codec.decode(tamperedToken, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedSignature() {
        SupportQueueCursorCodec codec = codecFor("secret-a");
        String token = codec.encode(sampleCursor());
        String[] parts = token.split("\\.", 2);

        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
        signature[0] ^= 0x01;
        String tamperedToken = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThatThrownBy(() -> codec.decode(tamperedToken, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldAcceptUnmodifiedTokenWithMatchingKey() {
        SupportQueueCursorCodec codec = codecFor("secret-a");
        SupportQueueCursor original = sampleCursor();
        String token = codec.encode(original);

        assertThat(codec.decode(token, NOW)).isEqualTo(original);
    }
}
