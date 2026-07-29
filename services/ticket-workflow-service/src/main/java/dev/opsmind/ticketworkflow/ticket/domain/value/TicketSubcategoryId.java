package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;
import java.util.UUID;

public record TicketSubcategoryId(UUID value) {

    public TicketSubcategoryId {
        Objects.requireNonNull(value, "subcategoryId must not be null");
    }

    public static TicketSubcategoryId of(UUID value) {
        return new TicketSubcategoryId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
