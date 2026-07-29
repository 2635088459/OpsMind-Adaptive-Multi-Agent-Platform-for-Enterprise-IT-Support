package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketTimelineCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-006 §15: the cursor is tamper-resistant — any payload or signature bit-flip invalidates it. */
@Tag("unit")
class TicketTimelineCursorSignatureTest {

    private static final Instant NOW = Instant.parse("2026-07-25T20:00:00Z");

    private TicketTimelineCursorCodec codecFor(String secret) {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", secret, new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        return new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
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
    void shouldRejectTokenSignedWithADifferentKey() {
        String token = codecFor("secret-a").encode(sampleCursor());

        assertThatThrownBy(() -> codecFor("secret-b").decode(token, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedPayload() {
        TicketTimelineCursorCodec codec = codecFor("secret-a");
        String token = codec.encode(sampleCursor());
        String[] parts = token.split("\\.", 2);

        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        payload[0] ^= 0x01;
        String tamperedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + parts[1];

        assertThatThrownBy(() -> codec.decode(tamperedToken, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedSignature() {
        TicketTimelineCursorCodec codec = codecFor("secret-a");
        String token = codec.encode(sampleCursor());
        String[] parts = token.split("\\.", 2);

        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
        signature[0] ^= 0x01;
        String tamperedToken = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThatThrownBy(() -> codec.decode(tamperedToken, NOW)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldAcceptUnmodifiedTokenWithMatchingKey() {
        TicketTimelineCursorCodec codec = codecFor("secret-a");
        TicketTimelineCursor original = sampleCursor();
        String token = codec.encode(original);

        assertThat(codec.decode(token, NOW)).isEqualTo(original);
    }
}
