package dev.opsmind.ticketworkflow.ticket.domain.message;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SPEC-TW-004 §7: 1-8000 characters after trim, no dangerous control
 * characters, and no secrets or credentials (BI-101 — secrets never enter
 * the Ticket domain). The rejection message never echoes the matched
 * secret text, only that the content was rejected.
 */
public record MessageContent(String value) {

    private static final int MAX_LENGTH = 8000;

    /**
     * High-confidence secret/credential patterns. Deliberately narrow
     * (anchored to well-known formats and explicit key=value/header
     * shapes) to avoid false-positives on ordinary support text that
     * merely mentions the word "password" or "token".
     */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("-----BEGIN\\s+(RSA |EC |DSA |OPENSSH |)PRIVATE KEY-----"),
        Pattern.compile("(?i)\\b(password|passwd|pwd)\\s*[:=]\\s*\\S+"),
        Pattern.compile("(?i)\\b(api[_-]?key|secret|access[_-]?key)\\s*[:=]\\s*\\S+"),
        Pattern.compile("(?i)\\bAuthorization\\s*:\\s*(Bearer|Basic)\\s+\\S+"),
        Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-_.]{10,}"),
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")
    );

    public MessageContent {
        Objects.requireNonNull(value, "content must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("content must be 1-" + MAX_LENGTH + " characters after trim");
        }
        if (containsDangerousControlCharacter(trimmed)) {
            throw new IllegalArgumentException("content must not contain control characters");
        }
        if (containsSecret(trimmed)) {
            throw new IllegalArgumentException("content must not contain secrets or credentials");
        }
        value = trimmed;
    }

    public static MessageContent of(String value) {
        return new MessageContent(value);
    }

    private static boolean containsDangerousControlCharacter(String candidate) {
        return candidate.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }

    private static boolean containsSecret(String candidate) {
        return SECRET_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(candidate).find());
    }

    @Override
    public String toString() {
        return value;
    }
}
