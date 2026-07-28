package dev.opsmind.ticketworkflow.ticket.domain.message;

import java.util.Objects;

/**
 * Always server-derived from the trusted principal — never accepted from
 * the request body (SPEC-TW-004 §4).
 */
public record MessageAuthor(String authorType, String authorId) {

    public MessageAuthor {
        Objects.requireNonNull(authorType, "authorType must not be null");
        Objects.requireNonNull(authorId, "authorId must not be null");
        if (authorType.isBlank()) {
            throw new IllegalArgumentException("authorType must not be blank");
        }
        if (authorId.isBlank()) {
            throw new IllegalArgumentException("authorId must not be blank");
        }
    }
}
