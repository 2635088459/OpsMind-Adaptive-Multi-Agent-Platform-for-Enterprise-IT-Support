package dev.opsmind.ticketworkflow.ticket.domain.exception;

import dev.opsmind.ticketworkflow.ticket.domain.value.SecretPatternCategory;

/**
 * Raised specifically when free text is rejected because it matched a
 * secret/credential pattern (SPEC-TW-035 domain-rules: "Free text
 * classified as secret-like must be rejected... Every policy decision
 * leaves an auditable decision code"), as opposed to an ordinary shape
 * violation (too long, blank, control characters). Extends {@link
 * IllegalArgumentException} so every existing catch site that only cared
 * "was this content invalid" (SPEC-TW-004) keeps working unchanged; callers
 * that need to tell secret-rejection apart from shape validation — to
 * record the SPEC-TW-035 audit ledger and return {@code 403} instead of
 * {@code 400} — catch this more specific type first. Never carries the
 * matched text, only the low-cardinality {@link #category()}.
 */
public class SecretContentDetectedException extends IllegalArgumentException {

    private final SecretPatternCategory category;

    public SecretContentDetectedException(SecretPatternCategory category) {
        super("content must not contain secrets or credentials");
        this.category = category;
    }

    public SecretPatternCategory category() {
        return category;
    }
}
