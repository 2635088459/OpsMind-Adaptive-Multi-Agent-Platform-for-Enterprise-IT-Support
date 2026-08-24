package com.opsmind.policygovernance.domain.approval;

import com.opsmind.policygovernance.domain.decision.Constraint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An approver's immutable outcome for one {@link ApprovalRequest}
 * (01-domain-model §ApprovalDecision).
 *
 * <p>INV-PG-003 ("Approval Must Be Unforgeable") requires {@code decidedBy}
 * to be present. INV-PG-004 ("Separation Of Duties Must Be Verified") is
 * enforced structurally here: an {@link Outcome#APPROVED} decision cannot be
 * constructed unless the caller has already set {@code
 * separationOfDutiesCheck}, so no code path can grant an approval without
 * that check having run.
 *
 * <p>{@code commandIdempotencyKey} is SPEC-PG-011's own addition —
 * 09-concurrency-and-idempotency §Idempotency Keys names "approval command:
 * approvalRequestId + commandIdempotencyKey" as its own distinct
 * idempotency-key type (separate from the request-linkage {@code
 * sourceRequestId}/{@code requestHash} pair INV-PG-005 already governs),
 * and 05-api-contracts says plainly "every command must include idempotency
 * key." {@code ApprovalService#decide} uses it, together with {@code
 * decision}/{@code decidedBy}, to tell a genuine retry of the same grant/deny
 * attempt apart from a different attempt that happens to share an outcome.
 *
 * <p>{@code sessionId}/{@code deviceId}/{@code stepUpVerified} are
 * SPEC-PG-016's own addition — the "full signature/session step-up model"
 * this type's javadoc used to defer, per 11-security §Approval Authenticity
 * ("Approval command must include: authenticated actor; session/device
 * metadata; idempotency key; reason; optional MFA/step-up marker;
 * correlation id"). {@code sessionId}/{@code deviceId} are nullable and
 * recorded verbatim for the audit trail — 06 has no session store of its
 * own (the platform's OAuth2/JWT setup is stateless,
 * {@code SessionCreationPolicy.STATELESS}), so it captures what an
 * upstream identity provider reports rather than independently validating a
 * live session. {@code stepUpVerified} defaults to {@code false} when the
 * caller supplies none (an absent marker is never treated as "verified");
 * {@code ApprovalService#decide} enforces it structurally for
 * {@code CRITICAL}-risk grants (the narrowest, most defensible reading of
 * "optional MFA/step-up marker" as a real security gate rather than inert
 * data collection) — see that method's own javadoc.
 */
public record ApprovalDecision(
    String approvalDecisionId,
    String approvalRequestId,
    Outcome decision,
    String decidedBy,
    Instant decidedAt,
    String reason,
    List<Constraint> conditions,
    boolean separationOfDutiesCheck,
    String commandIdempotencyKey,
    String sessionId,
    String deviceId,
    boolean stepUpVerified
) {

    public ApprovalDecision {
        Objects.requireNonNull(approvalDecisionId, "approvalDecisionId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy is required (INV-PG-003: approval must be unforgeable)");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required for an explainable approval decision");
        }
        if (commandIdempotencyKey == null || commandIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("commandIdempotencyKey is required (09-concurrency-and-idempotency)");
        }
        if (decision == Outcome.APPROVED && !separationOfDutiesCheck) {
            throw new SeparationOfDutiesNotVerifiedException(approvalRequestId);
        }
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    public enum Outcome {
        APPROVED,
        DENIED
    }
}
