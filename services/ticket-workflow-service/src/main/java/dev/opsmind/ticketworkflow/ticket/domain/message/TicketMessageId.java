package dev.opsmind.ticketworkflow.ticket.domain.message;

import java.util.Objects;
import java.util.UUID;

public record TicketMessageId(UUID value) {

    public TicketMessageId {
        Objects.requireNonNull(value, "messageId must not be null");
    }

    public static TicketMessageId of(UUID value) {
        return new TicketMessageId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
