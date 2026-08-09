package dev.opsmind.ticketworkflow.ticket.application.policy;

import dev.opsmind.ticketworkflow.ticket.application.command.StepUpProof;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Pure step-up proof validity rules for SPEC-TW-036 (domain-rules:
 * "High-risk commands without valid step-up proof must be rejected before
 * business mutation"). {@code RECOGNIZED_OPERATIONS} is the phase-09
 * cross-cutting high-risk set: cancel, close, reopen a closed ticket,
 * escalate, and the auto-approved high-risk policy path, plus the shared
 * contract's generic command operation. Contains no I/O: the proof itself
 * is resolved by the caller, either from the actor's own trusted JWT
 * claims (a human-facing command) or from the internal endpoint's request
 * body (a different caller/subject principal).
 */
@Component
public class StepUpAuthenticationPolicy {

    /** phase-09 §4 cross-cutting table: "cancel, close, reopen closed ticket, escalate, auto-approved high-risk policy". */
    public static final Set<String> RECOGNIZED_OPERATIONS = Set.of(
        "ticket.command", "ticket.cancel", "ticket.close", "ticket.reopen", "ticket.escalate", "ticket.policy.auto-approve"
    );

    /** Tolerance for a {@code verifiedAt} that is very slightly in the future due to clock skew between the IdP and this service. */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(5);

    public boolean isRecognizedOperation(String operation) {
        return operation != null && RECOGNIZED_OPERATIONS.contains(operation);
    }

    public boolean isValid(StepUpProof proof, Instant now) {
        return StepUpAuthenticationDecisionCode.ALLOWED.equals(classify(proof, now));
    }

    /** @return {@link StepUpAuthenticationDecisionCode#ALLOWED} or one of the {@code DENIED_STEP_UP_*} codes. */
    public String classify(StepUpProof proof, Instant now) {
        if (proof == null) {
            return StepUpAuthenticationDecisionCode.DENIED_STEP_UP_MISSING;
        }
        if (isBlank(proof.proofId()) || isBlank(proof.method()) || proof.verifiedAt() == null || proof.expiresAt() == null) {
            return StepUpAuthenticationDecisionCode.DENIED_STEP_UP_INVALID;
        }
        if (proof.verifiedAt().isAfter(proof.expiresAt()) || proof.verifiedAt().isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            return StepUpAuthenticationDecisionCode.DENIED_STEP_UP_INVALID;
        }
        if (!proof.expiresAt().isAfter(now)) {
            return StepUpAuthenticationDecisionCode.DENIED_STEP_UP_EXPIRED;
        }
        return StepUpAuthenticationDecisionCode.ALLOWED;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
