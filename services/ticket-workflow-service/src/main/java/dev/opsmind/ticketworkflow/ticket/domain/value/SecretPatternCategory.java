package dev.opsmind.ticketworkflow.ticket.domain.value;

/**
 * Low-cardinality classification of a detected secret-like pattern
 * (SPEC-TW-035 domain-rules: "Audit and telemetry must not contain
 * secrets... or high-cardinality fields"). Never carries the matched text
 * itself — only which fixed category of pattern matched, or {@link #NONE}.
 */
public enum SecretPatternCategory {
    NONE,
    PRIVATE_KEY_BLOCK,
    PASSWORD_ASSIGNMENT,
    API_KEY_ASSIGNMENT,
    AUTHORIZATION_HEADER,
    BEARER_TOKEN,
    AWS_ACCESS_KEY
}
