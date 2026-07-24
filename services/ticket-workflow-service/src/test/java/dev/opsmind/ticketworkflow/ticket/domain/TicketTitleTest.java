package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.value.TicketTitle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class TicketTitleTest {

    @Test
    void shouldTrimSurroundingWhitespace() {
        assertThat(TicketTitle.of("  Cannot sign in  ").value()).isEqualTo("Cannot sign in");
    }

    @Test
    void shouldRejectBlankTitle() {
        assertThatThrownBy(() -> TicketTitle.of("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNull() {
        assertThatThrownBy(() -> TicketTitle.of(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAcceptExactly200CharactersAfterTrim() {
        String title = "a".repeat(200);
        assertThat(TicketTitle.of(title).value()).hasSize(200);
    }

    @Test
    void shouldRejectMoreThan200CharactersAfterTrim() {
        String title = "a".repeat(201);
        assertThatThrownBy(() -> TicketTitle.of(title))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEmbeddedControlCharacters() {
        String titleWithControlCharacter = "Cannot" + '\u0007' + "sign in";

        assertThatThrownBy(() -> TicketTitle.of(titleWithControlCharacter))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
