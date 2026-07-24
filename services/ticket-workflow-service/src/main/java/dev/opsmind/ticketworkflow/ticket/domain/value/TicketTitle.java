package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.Objects;

public record TicketTitle(String value) {

    private static final int MAX_LENGTH = 200;

    public TicketTitle {
        Objects.requireNonNull(value, "title must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("title must be 1-" + MAX_LENGTH + " characters after trim");
        }
        if (containsControlCharacter(trimmed)) {
            throw new IllegalArgumentException("title must not contain control characters");
        }
        value = trimmed;
    }

    private static boolean containsControlCharacter(String candidate) {
        return candidate.chars().anyMatch(Character::isISOControl);
    }

    public static TicketTitle of(String value) {
        return new TicketTitle(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
