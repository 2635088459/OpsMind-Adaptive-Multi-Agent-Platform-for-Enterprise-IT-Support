package dev.opsmind.ticketworkflow.ticket.domain;

import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-004 §6: the type-to-visibility mapping is fixed and never an
 * independent client input.
 */
@Tag("unit")
class TicketMessageTypeVisibilityTest {

    @Test
    void publicRequesterMessageShouldBePublic() {
        assertThat(TicketMessageType.PUBLIC_REQUESTER_MESSAGE.visibility()).isEqualTo(MessageVisibility.PUBLIC);
    }

    @Test
    void publicSupportMessageShouldBePublic() {
        assertThat(TicketMessageType.PUBLIC_SUPPORT_MESSAGE.visibility()).isEqualTo(MessageVisibility.PUBLIC);
    }

    @Test
    void internalSupportNoteShouldBeInternal() {
        assertThat(TicketMessageType.INTERNAL_SUPPORT_NOTE.visibility()).isEqualTo(MessageVisibility.INTERNAL);
    }
}
