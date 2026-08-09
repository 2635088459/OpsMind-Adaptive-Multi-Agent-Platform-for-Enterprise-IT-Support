package dev.opsmind.ticketworkflow.ticket.domain.message;

import dev.opsmind.ticketworkflow.ticket.domain.exception.SecretContentDetectedException;
import dev.opsmind.ticketworkflow.ticket.domain.value.SecretPatternCategory;
import dev.opsmind.ticketworkflow.ticket.domain.value.SecretPatternDetector;

import java.util.Objects;

/**
 * SPEC-TW-004 §7: 1-8000 characters after trim, no dangerous control
 * characters, and no secrets or credentials (BI-101 — secrets never enter
 * the Ticket domain). The rejection message never echoes the matched
 * secret text, only that the content was rejected. Secret/credential
 * detection itself is delegated to the shared {@link SecretPatternDetector}
 * (SPEC-TW-035 hardening) rather than a private pattern list, so every
 * free-text field this codebase validates uses one ruleset.
 */
public record MessageContent(String value) {

    private static final int MAX_LENGTH = 8000;

    public MessageContent {
        Objects.requireNonNull(value, "content must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("content must be 1-" + MAX_LENGTH + " characters after trim");
        }
        if (containsDangerousControlCharacter(trimmed)) {
            throw new IllegalArgumentException("content must not contain control characters");
        }
        SecretPatternCategory category = SecretPatternDetector.classify(trimmed);
        if (category != SecretPatternCategory.NONE) {
            throw new SecretContentDetectedException(category);
        }
        value = trimmed;
    }

    public static MessageContent of(String value) {
        return new MessageContent(value);
    }

    private static boolean containsDangerousControlCharacter(String candidate) {
        return candidate.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }

    @Override
    public String toString() {
        return value;
    }
}
