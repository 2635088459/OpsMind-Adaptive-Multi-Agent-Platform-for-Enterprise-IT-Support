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

/** SPEC-TW-005 §14: the cursor expires 1 hour after issuance. */
@Tag("unit")
class SupportQueueCursorExpiryTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-25T19:00:00Z");

    private SupportQueueCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        codec = new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));
    }

    private String tokenIssuedAt(Instant issuedAt, Duration ttl) {
        return codec.encode(new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, issuedAt, 0, 0, issuedAt, UUID.randomUUID(),
            "sha256:filters", "sha256:scope", "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, issuedAt, issuedAt.plus(ttl)
        ));
    }

    @Test
    void shouldAcceptCursorWellWithinTtl() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(1));

        assertThatCode(() -> codec.decode(token, ISSUED_AT.plusSeconds(60))).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptCursorOneSecondBeforeExpiry() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(1));

        assertThatCode(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(1)).minusSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectCursorAtExactExpiryInstant() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(1));

        assertThatThrownBy(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(1))))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectCursorOlderThanTtl() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(1));

        assertThatThrownBy(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(2))))
            .isInstanceOf(InvalidCursorException.class);
    }
}
