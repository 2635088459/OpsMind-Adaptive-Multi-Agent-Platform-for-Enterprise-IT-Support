package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;
import java.util.UUID;

public record TicketId(UUID value) {

    public TicketId {
        Objects.requireNonNull(value, "ticketId must not be null");
    }

    public static TicketId of(UUID value) {
        return new TicketId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
