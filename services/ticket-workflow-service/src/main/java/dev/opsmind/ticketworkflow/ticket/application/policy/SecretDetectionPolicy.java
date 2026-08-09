package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.domain.value.SecretPatternCategory;
import dev.opsmind.ticketworkflow.ticket.domain.value.SecretPatternDetector;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Application-layer wrapper around the shared, pure {@link
 * SecretPatternDetector} (SPEC-TW-035). Maps a detected {@link
 * SecretPatternCategory} to this SPEC's low-cardinality decisionCode
 * vocabulary and recognizes the free-text-bearing operations this policy
 * governs (README §1: "messages, reasons, audit free text, and outbox
 * payloads").
 */
@Component
public class SecretDetectionPolicy {

    /** Ticket message create/update (SPEC-TW-004/SPEC-TW-019) and the shared contract's generic command operation. */
    public static final Set<String> RECOGNIZED_OPERATIONS = Set.of("ticket.command", "ticket.message.create", "ticket.message.update");

    private static final Map<SecretPatternCategory, String> DECISION_CODE_BY_CATEGORY = Map.of(
        SecretPatternCategory.PRIVATE_KEY_BLOCK, SecretDetectionDecisionCode.DENIED_PRIVATE_KEY_BLOCK,
        SecretPatternCategory.PASSWORD_ASSIGNMENT, SecretDetectionDecisionCode.DENIED_PASSWORD_ASSIGNMENT,
        SecretPatternCategory.API_KEY_ASSIGNMENT, SecretDetectionDecisionCode.DENIED_API_KEY_ASSIGNMENT,
        SecretPatternCategory.AUTHORIZATION_HEADER, SecretDetectionDecisionCode.DENIED_AUTHORIZATION_HEADER,
        SecretPatternCategory.BEARER_TOKEN, SecretDetectionDecisionCode.DENIED_BEARER_TOKEN,
        SecretPatternCategory.AWS_ACCESS_KEY, SecretDetectionDecisionCode.DENIED_AWS_ACCESS_KEY
    );

    public boolean isRecognizedOperation(String operation) {
        return operation != null && RECOGNIZED_OPERATIONS.contains(operation);
    }

    /** @return {@link SecretPatternCategory#NONE} when {@code content} is blank or clean. */
    public SecretPatternCategory classify(String content) {
        return SecretPatternDetector.classify(content);
    }

    /** @throws IllegalArgumentException if {@code category} is {@link SecretPatternCategory#NONE} (an ALLOW is not a denial decisionCode). */
    public String decisionCodeFor(SecretPatternCategory category) {
        String decisionCode = DECISION_CODE_BY_CATEGORY.get(category);
        if (decisionCode == null) {
            throw new IllegalArgumentException("no denial decisionCode for category " + category);
        }
        return decisionCode;
    }
}
