package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketCreated;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketDomainEvent;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class TicketCreationTest {

    private static final Instant NOW = Instant.parse("2026-07-23T16:30:00Z");

    @Test
    void shouldCreateTicketWithNewStatusAndUnassignedPriority() {
        TicketId ticketId = TicketId.of(UUID.randomUUID());
        TicketDisplayId displayId = TicketDisplayId.of("INC-2048");
        RequesterId requesterId = RequesterId.of("user-123");
        UUID resolutionCycleId = UUID.randomUUID();

        Ticket ticket = Ticket.create(
            ticketId,
            displayId,
            requesterId,
            TicketTitle.of("Cannot sign in"),
            TicketDescription.of("Duo keeps asking me to enroll again."),
            ApplicationCode.HOUSING_PORTAL,
            TicketSource.PORTAL,
            resolutionCycleId,
            NOW
        );

        assertThat(ticket.id()).isEqualTo(ticketId);
        assertThat(ticket.displayId()).isEqualTo(displayId);
        assertThat(ticket.requesterId()).isEqualTo(requesterId);
        assertThat(ticket.status()).isEqualTo(TicketStatus.NEW);
        assertThat(ticket.priority()).isEqualTo(TicketPriority.UNASSIGNED);
        assertThat(ticket.category()).isNull();
        assertThat(ticket.subcategory()).isNull();
        assertThat(ticket.activeWorkflowId()).isNull();
        assertThat(ticket.currentSupportUserId()).isNull();
        assertThat(ticket.currentTeamId()).isNull();
        assertThat(ticket.currentResolutionCycleId()).isEqualTo(resolutionCycleId);
        assertThat(ticket.resolvedAt()).isNull();
        assertThat(ticket.closedAt()).isNull();
        assertThat(ticket.cancelledAt()).isNull();
        assertThat(ticket.createdAt()).isEqualTo(NOW);
        assertThat(ticket.updatedAt()).isEqualTo(NOW);
        assertThat(ticket.version()).isZero();
        assertThat(ticket.createdByType()).isEqualTo("EMPLOYEE");
        assertThat(ticket.createdById()).isEqualTo("user-123");
    }

    @Test
    void shouldEmitTicketCreatedDomainEvent() {
        TicketId ticketId = TicketId.of(UUID.randomUUID());
        TicketDisplayId displayId = TicketDisplayId.of("INC-2049");
        RequesterId requesterId = RequesterId.of("user-456");

        Ticket ticket = Ticket.create(
            ticketId,
            displayId,
            requesterId,
            TicketTitle.of("VPN not connecting"),
            TicketDescription.of("Authentication fails every time."),
            ApplicationCode.VPN,
            TicketSource.PORTAL,
            UUID.randomUUID(),
            NOW
        );

        List<TicketDomainEvent> events = ticket.pullDomainEvents();

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOfSatisfying(TicketCreated.class, event -> {
            assertThat(event.ticketId()).isEqualTo(ticketId);
            assertThat(event.displayId()).isEqualTo(displayId);
            assertThat(event.requesterId()).isEqualTo(requesterId);
            assertThat(event.applicationCode()).isEqualTo(ApplicationCode.VPN);
            assertThat(event.source()).isEqualTo(TicketSource.PORTAL);
            assertThat(event.aggregateVersion()).isZero();
            assertThat(event.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    void shouldClearDomainEventsAfterPull() {
        Ticket ticket = Ticket.create(
            TicketId.of(UUID.randomUUID()),
            TicketDisplayId.of("INC-2050"),
            RequesterId.of("user-789"),
            TicketTitle.of("Email not syncing"),
            TicketDescription.of("Outlook stopped receiving new messages."),
            ApplicationCode.EMAIL,
            TicketSource.PORTAL,
            UUID.randomUUID(),
            NOW
        );

        ticket.pullDomainEvents();

        assertThat(ticket.pullDomainEvents()).isEmpty();
    }

    @Test
    void shouldRejectNullRequiredArguments() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Ticket.create(
            null,
            TicketDisplayId.of("INC-2051"),
            RequesterId.of("user-999"),
            TicketTitle.of("Title"),
            TicketDescription.of("Description"),
            ApplicationCode.OTHER,
            TicketSource.PORTAL,
            UUID.randomUUID(),
            NOW
        )).isInstanceOf(NullPointerException.class);
    }
}
