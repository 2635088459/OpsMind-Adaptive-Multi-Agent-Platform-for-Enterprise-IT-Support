package dev.opsmind.ticketworkflow.ticket.domain.value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure, shared secret/credential pattern matcher (SPEC-TW-004 §7 BI-101,
 * hardened and centralized by SPEC-TW-035). Originally private to {@link
 * dev.opsmind.ticketworkflow.ticket.domain.message.MessageContent}; extracted
 * here, unchanged, so every free-text field this domain validates — Ticket
 * messages today, reason fields under SPEC-TW-035 hardening — shares
 * exactly one detection ruleset instead of each duplicating its own copy.
 * Deliberately narrow (anchored to well-known formats and explicit
 * key=value/header shapes) to avoid false positives on ordinary support
 * text that merely mentions the word "password" or "token". Contains no
 * Spring/JPA dependency (domain layer).
 */
public final class SecretPatternDetector {

    private static final Map<SecretPatternCategory, Pattern> PATTERNS_BY_CATEGORY = new LinkedHashMap<>();

    static {
        PATTERNS_BY_CATEGORY.put(SecretPatternCategory.PRIVATE_KEY_BLOCK,
            Pattern.compile("-----BEGIN\\s+(RSA |EC |DSA |OPENSSH |)PRIVATE KEY-----"));
        PATTERNS_BY_CATEGORY.put(SecretPatternCategory.PASSWORD_ASSIGNMENT,
            Pattern.compile("(?i)\\b(password|passwd|pwd)\\s*[:=]\\s*\\S+"));
        PATTERNS_BY_CATEGORY.put(SecretPatternCategory.API_KEY_ASSIGNMENT,
            Pattern.compile("(?i)\\b(api[_-]?key|secret|access[_-]?key)\\s*[:=]\\s*\\S+"));
        PATTERNS_BY_CATEGORY.put(SecretPatternCategory.AUTHORIZATION_HEADER,
            Pattern.compile("(?i)\\bAuthorization\\s*:\\s*(Bearer|Basic)\\s+\\S+"));
        PATTERNS_BY_CATEGORY.put(SecretPatternCategory.BEARER_TOKEN,
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-_.]{10,}"));
        PATTERNS_BY_CATEGORY.put(SecretPatternCategory.AWS_ACCESS_KEY,
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"));
    }

    private SecretPatternDetector() {
    }

    /** Returns the first matching category in declaration order, or {@link SecretPatternCategory#NONE} if the text is clean or blank. */
    public static SecretPatternCategory classify(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return SecretPatternCategory.NONE;
        }
        for (Map.Entry<SecretPatternCategory, Pattern> entry : PATTERNS_BY_CATEGORY.entrySet()) {
            if (entry.getValue().matcher(candidate).find()) {
                return entry.getKey();
            }
        }
        return SecretPatternCategory.NONE;
    }

    public static boolean containsSecret(String candidate) {
        return classify(candidate) != SecretPatternCategory.NONE;
    }
}
