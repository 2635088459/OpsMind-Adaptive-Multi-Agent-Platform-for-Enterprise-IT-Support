package dev.opsmind.ticketworkflow.ticket.application;

import dev.opsmind.ticketworkflow.ticket.application.query.SlaQueueState;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueuePriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-005 §11/§12: frozen rank scales — 0 is always the most urgent/highest priority. */
@Tag("unit")
class SupportQueueSortRankingTest {

    @Test
    void slaRanksShouldBeFrozenAsSpecified() {
        assertThat(SlaQueueState.BREACHED.urgencyRank()).isEqualTo(0);
        assertThat(SlaQueueState.AT_RISK.urgencyRank()).isEqualTo(1);
        assertThat(SlaQueueState.ACTIVE.urgencyRank()).isEqualTo(2);
        assertThat(SlaQueueState.PAUSED.urgencyRank()).isEqualTo(3);
        assertThat(SlaQueueState.COMPLETED.urgencyRank()).isEqualTo(4);
    }

    @Test
    void priorityRanksShouldBeFrozenAsSpecified() {
        assertThat(SupportQueuePriority.P1.priorityRank()).isEqualTo(0);
        assertThat(SupportQueuePriority.P2.priorityRank()).isEqualTo(1);
        assertThat(SupportQueuePriority.P3.priorityRank()).isEqualTo(2);
        assertThat(SupportQueuePriority.P4.priorityRank()).isEqualTo(3);
        assertThat(SupportQueuePriority.UNASSIGNED.priorityRank()).isEqualTo(4);
    }

    @Test
    void p1ShouldMapToTheMostSevereTicketPriority() {
        assertThat(SupportQueuePriority.P1.ticketPriority()).isEqualTo(TicketPriority.CRITICAL);
        assertThat(SupportQueuePriority.P4.ticketPriority()).isEqualTo(TicketPriority.LOW);
        assertThat(SupportQueuePriority.UNASSIGNED.ticketPriority()).isEqualTo(TicketPriority.UNASSIGNED);
    }

    @Test
    void fromTicketPriorityShouldRoundTripForEveryValue() {
        for (TicketPriority priority : TicketPriority.values()) {
            SupportQueuePriority mapped = SupportQueuePriority.fromTicketPriority(priority);
            assertThat(mapped.ticketPriority()).isEqualTo(priority);
        }
    }
}
