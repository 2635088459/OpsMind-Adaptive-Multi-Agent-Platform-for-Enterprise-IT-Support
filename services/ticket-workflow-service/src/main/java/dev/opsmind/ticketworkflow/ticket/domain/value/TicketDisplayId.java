package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;
import java.util.regex.Pattern;

public record TicketDisplayId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^INC-[1-9][0-9]*$");

    public TicketDisplayId {
        Objects.requireNonNull(value, "displayId must not be null");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("displayId must match format INC-<number>: " + value);
        }
    }

    public static TicketDisplayId of(String value) {
        return new TicketDisplayId(value);
    }

    public static TicketDisplayId fromSequence(long sequenceValue) {
        if (sequenceValue <= 0) {
            throw new IllegalArgumentException("sequenceValue must be positive");
        }
        return new TicketDisplayId("INC-" + sequenceValue);
    }

    @Override
    public String toString() {
        return value;
    }
}
