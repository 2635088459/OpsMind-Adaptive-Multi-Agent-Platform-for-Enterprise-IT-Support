package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.event.TicketTriaged;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.value.SupportQueueId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketCategoryId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSubcategoryId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Ticket#triage} (SPEC-TW-007 §2/§3). This codebase's pre-triage
 * status is {@link TicketStatus#NEW} (the spec's "OPEN"); see {@link
 * TicketStatus}'s Javadoc for the reconciliation. Every other enum value,
 * including {@link TicketStatus#TRIAGING} (not the same as the post-triage
 * {@link TicketStatus#TRIAGED}), is a rejected source status.
 */
@Tag("unit")
class TicketTriageTest {

    private static final Instant NOW = Instant.parse("2026-07-29T18:30:00Z");
    private static final TicketId TICKET_ID = TicketId.of(UUID.randomUUID());
    private static final TicketCategoryId CATEGORY_ID = TicketCategoryId.of(UUID.randomUUID());
    private static final TicketSubcategoryId SUBCATEGORY_ID = TicketSubcategoryId.of(UUID.randomUUID());
    private static final SupportQueueId SUPPORT_QUEUE_ID = SupportQueueId.of(UUID.randomUUID());

    @Test
    void shouldTriageAnOpenTicketAndIncrementVersionExactlyOnce() {
        TicketTriaged event = Ticket.triage(
            TICKET_ID,
            TicketStatus.NEW,
            7L,
            CATEGORY_ID,
            SUBCATEGORY_ID,
            TicketPriority.HIGH,
            SUPPORT_QUEUE_ID,
            "IT_SUPPORT",
            "support-100",
            NOW
        );

        assertThat(event.ticketId()).isEqualTo(TICKET_ID);
        assertThat(event.fromStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(event.toStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(event.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(event.subcategoryId()).isEqualTo(SUBCATEGORY_ID);
        assertThat(event.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(event.supportQueueId()).isEqualTo(SUPPORT_QUEUE_ID);
        assertThat(event.triagedByActorType()).isEqualTo("IT_SUPPORT");
        assertThat(event.triagedByActorId()).isEqualTo("support-100");
        assertThat(event.aggregateVersion()).isEqualTo(8L);
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = "NEW", mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectTriageFromEveryNonNewStatus(TicketStatus currentStatus) {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID,
            currentStatus,
            3L,
            CATEGORY_ID,
            SUBCATEGORY_ID,
            TicketPriority.MEDIUM,
            SUPPORT_QUEUE_ID,
            "IT_SUPPORT",
            "support-100",
            NOW
        ))
            .isInstanceOfSatisfying(InvalidTicketTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(currentStatus);
                assertThat(ex.requiredStatus()).isEqualTo(TicketStatus.NEW);
            });
    }

    @Test
    void shouldAcceptANullSubcategoryAndCarryItThroughAsNull() {
        TicketTriaged event = Ticket.triage(
            TICKET_ID,
            TicketStatus.NEW,
            0L,
            CATEGORY_ID,
            null,
            TicketPriority.LOW,
            SUPPORT_QUEUE_ID,
            "IT_SUPPORT",
            "support-100",
            NOW
        );

        assertThat(event.subcategoryId()).isNull();
    }

    @Test
    void shouldRejectUnassignedPriority() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID,
            TicketStatus.NEW,
            0L,
            CATEGORY_ID,
            SUBCATEGORY_ID,
            TicketPriority.UNASSIGNED,
            SUPPORT_QUEUE_ID,
            "IT_SUPPORT",
            "support-100",
            NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTicketId() {
        assertThatThrownBy(() -> Ticket.triage(
            null, TicketStatus.NEW, 0L, CATEGORY_ID, SUBCATEGORY_ID, TicketPriority.LOW, SUPPORT_QUEUE_ID, "IT_SUPPORT", "support-100", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCurrentStatus() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, null, 0L, CATEGORY_ID, SUBCATEGORY_ID, TicketPriority.LOW, SUPPORT_QUEUE_ID, "IT_SUPPORT", "support-100", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCategoryId() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, TicketStatus.NEW, 0L, null, SUBCATEGORY_ID, TicketPriority.LOW, SUPPORT_QUEUE_ID, "IT_SUPPORT", "support-100", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPriority() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, TicketStatus.NEW, 0L, CATEGORY_ID, SUBCATEGORY_ID, null, SUPPORT_QUEUE_ID, "IT_SUPPORT", "support-100", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSupportQueueId() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, TicketStatus.NEW, 0L, CATEGORY_ID, SUBCATEGORY_ID, TicketPriority.LOW, null, "IT_SUPPORT", "support-100", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTriagedByActorType() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, TicketStatus.NEW, 0L, CATEGORY_ID, SUBCATEGORY_ID, TicketPriority.LOW, SUPPORT_QUEUE_ID, null, "support-100", NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTriagedByActorId() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, TicketStatus.NEW, 0L, CATEGORY_ID, SUBCATEGORY_ID, TicketPriority.LOW, SUPPORT_QUEUE_ID, "IT_SUPPORT", null, NOW
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOccurredAt() {
        assertThatThrownBy(() -> Ticket.triage(
            TICKET_ID, TicketStatus.NEW, 0L, CATEGORY_ID, SUBCATEGORY_ID, TicketPriority.LOW, SUPPORT_QUEUE_ID, "IT_SUPPORT", "support-100", null
        )).isInstanceOf(NullPointerException.class);
    }
}
