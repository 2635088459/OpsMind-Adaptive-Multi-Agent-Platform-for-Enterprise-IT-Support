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

/** SPEC-TW-006 §15: the cursor expires 24 hours after issuance. */
@Tag("unit")
class TicketTimelineCursorExpiryTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-25T20:00:00Z");

    private TicketTimelineCursorCodec codec;

    @BeforeEach
    void setUp() {
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        );
        codec = new TicketTimelineCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketTimelineCursorSigner(properties));
    }

    private String tokenIssuedAt(Instant issuedAt, Duration ttl) {
        return codec.encode(new TicketTimelineCursor(
            TicketTimelineCursor.CURRENT_VERSION, UUID.randomUUID().toString(), "employee-123", "sha256:scope",
            "EMPLOYEE_PUBLIC_VIEW", TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION, issuedAt,
            issuedAt, 0, "TICKET_CREATED:x", TicketTimelineSortVersion.CURRENT_VERSION,
            TicketTimelineCursor.OPERATION, issuedAt, issuedAt.plus(ttl)
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
