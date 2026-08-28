package com.opsmind.identity.application.command;

import com.opsmind.identity.domain.breakglass.ApprovalOutcome;

/**
 * SPEC-UA-028. {@code approvalRequestId} is domain 06's own aggregate id —
 * the exact value a caller asserts as a {@code BreakGlassGrant#approvalReference}
 * when activating break-glass access (UA-019's own "asserted by the trusted
 * caller, never independently validated" — this is that independent
 * validation, arriving asynchronously).
 */
public record ReconcileApprovalOutcomeCommand(
    String approvalRequestId,
    ApprovalOutcome outcome,
    String correlationId
) {
}
