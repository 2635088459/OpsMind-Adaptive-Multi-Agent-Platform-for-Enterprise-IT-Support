package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;

public record TicketDescription(String value) {

    private static final int MAX_LENGTH = 10_000;

    public TicketDescription {
        Objects.requireNonNull(value, "description must not be null");
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("description must be 1-" + MAX_LENGTH + " characters and not blank");
        }
    }

    public static TicketDescription of(String value) {
        return new TicketDescription(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
