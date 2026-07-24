package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDescription;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class TicketDescriptionTest {

    @Test
    void shouldAcceptValidDescription() {
        assertThat(TicketDescription.of("Duo keeps asking me to enroll again.").value())
            .isEqualTo("Duo keeps asking me to enroll again.");
    }

    @Test
    void shouldRejectBlank() {
        assertThatThrownBy(() -> TicketDescription.of("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNull() {
        assertThatThrownBy(() -> TicketDescription.of(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAcceptExactly10000Characters() {
        String description = "a".repeat(10_000);
        assertThat(TicketDescription.of(description).value()).hasSize(10_000);
    }

    @Test
    void shouldRejectMoreThan10000Characters() {
        String description = "a".repeat(10_001);
        assertThatThrownBy(() -> TicketDescription.of(description))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
