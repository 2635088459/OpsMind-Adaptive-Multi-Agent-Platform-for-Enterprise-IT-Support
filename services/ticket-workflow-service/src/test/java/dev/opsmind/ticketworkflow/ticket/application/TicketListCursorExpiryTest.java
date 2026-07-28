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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-TW-003 §8: the cursor expires 24 hours after issuance. */
@Tag("unit")
class TicketListCursorExpiryTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-25T18:00:00Z");

    private TicketListCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        codec = new TicketListCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketListCursorSigner(properties));
    }

    private String tokenIssuedAt(Instant issuedAt, Duration ttl) {
        return codec.encode(new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, issuedAt, UUID.randomUUID(), "sha256:abc",
            TicketListCursor.SORT, "employee-123", TicketListCursor.OPERATION, issuedAt, issuedAt.plus(ttl)
        ));
    }

    @Test
    void shouldAcceptCursorWellWithinTtl() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(24));

        assertThatCode(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(1)))).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptCursorOneSecondBeforeExpiry() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(24));

        assertThatCode(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(24)).minusSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectCursorAtExactExpiryInstant() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(24));

        assertThatThrownBy(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(24))))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectCursorOlderThanTtl() {
        String token = tokenIssuedAt(ISSUED_AT, Duration.ofHours(24));

        assertThatThrownBy(() -> codec.decode(token, ISSUED_AT.plus(Duration.ofHours(25))))
            .isInstanceOf(InvalidCursorException.class);
    }
}
