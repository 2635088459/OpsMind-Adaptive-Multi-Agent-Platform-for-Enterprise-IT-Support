package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;

public record RequesterId(String value) {

    public RequesterId {
        Objects.requireNonNull(value, "requesterId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("requesterId must not be blank");
        }
    }

    public static RequesterId of(String value) {
        return new RequesterId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
