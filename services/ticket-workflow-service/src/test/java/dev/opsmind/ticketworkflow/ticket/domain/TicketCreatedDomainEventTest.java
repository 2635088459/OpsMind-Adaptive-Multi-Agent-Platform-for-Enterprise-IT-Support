package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class TicketCreatedDomainEventTest {

    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final TicketDisplayId DISPLAY_ID = TicketDisplayId.of("INC-1");
    private static final RequesterId REQUESTER_ID = RequesterId.of("user-1");
    private static final Instant NOW = Instant.parse("2026-07-23T16:30:00Z");

    @Test
    void shouldRejectMissingTicketId() {
        assertThatThrownBy(() -> new TicketCreated(
            null, DISPLAY_ID, REQUESTER_ID, ApplicationCode.OTHER, TicketSource.PORTAL, 0L, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectMissingRequesterId() {
        assertThatThrownBy(() -> new TicketCreated(
            TICKET_ID, DISPLAY_ID, null, ApplicationCode.OTHER, TicketSource.PORTAL, 0L, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectMissingCreatedAt() {
        assertThatThrownBy(() -> new TicketCreated(
            TICKET_ID, DISPLAY_ID, REQUESTER_ID, ApplicationCode.OTHER, TicketSource.PORTAL, 0L, null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCarryOnlyDomainFieldsWithNoRoutingOrSerializationBehavior() {
        // The domain event is a plain record with no Jackson annotations or
        // messaging-specific members; absence of any Spring/JPA/Jackson
        // dependency from this package is enforced by LayerDependencyTest.
        TicketCreated event = new TicketCreated(
            TICKET_ID, DISPLAY_ID, REQUESTER_ID, ApplicationCode.HOUSING_PORTAL, TicketSource.PORTAL, 0L, NOW
        );

        assertThat(event.getClass().getPackageName())
            .isEqualTo("dev.opsmind.ticketworkflow.ticket.domain.event");
        assertThat(event.ticketId()).isEqualTo(TICKET_ID);
        assertThat(event.displayId()).isEqualTo(DISPLAY_ID);
        assertThat(event.requesterId()).isEqualTo(REQUESTER_ID);
        assertThat(event.applicationCode()).isEqualTo(ApplicationCode.HOUSING_PORTAL);
        assertThat(event.source()).isEqualTo(TicketSource.PORTAL);
        assertThat(event.aggregateVersion()).isZero();
        assertThat(event.createdAt()).isEqualTo(NOW);
    }
}
