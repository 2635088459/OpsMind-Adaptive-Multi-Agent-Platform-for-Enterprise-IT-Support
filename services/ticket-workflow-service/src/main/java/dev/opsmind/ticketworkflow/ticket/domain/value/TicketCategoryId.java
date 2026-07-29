package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;
import java.util.UUID;

public record TicketCategoryId(UUID value) {

    public TicketCategoryId {
        Objects.requireNonNull(value, "categoryId must not be null");
    }

    public static TicketCategoryId of(UUID value) {
        return new TicketCategoryId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
