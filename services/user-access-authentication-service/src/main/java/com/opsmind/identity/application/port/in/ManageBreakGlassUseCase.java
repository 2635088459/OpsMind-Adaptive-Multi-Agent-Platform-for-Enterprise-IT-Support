package com.opsmind.identity.application.port.in;

import com.opsmind.identity.application.command.ActivateBreakGlassCommand;
import com.opsmind.identity.application.command.ReconcileApprovalOutcomeCommand;
import com.opsmind.identity.application.command.RevokeBreakGlassCommand;
import com.opsmind.identity.domain.breakglass.BreakGlassGrant;

/** SPEC-UA-019 (Break Glass And Account Recovery — 04-use-cases §Break-glass). */
public interface ManageBreakGlassUseCase {

    /** @throws com.opsmind.identity.application.exception.BreakGlassActivationDeniedException when any precondition (strong auth, approval reference, reason, bounded duration) is not met. */
    BreakGlassGrant activate(ActivateBreakGlassCommand command);

    BreakGlassGrant revoke(RevokeBreakGlassCommand command);

    BreakGlassGrant findById(String breakGlassGrantId);

    /** 04-use-cases §Break-glass: "Auto-expire" — admin/scheduler-triggered. */
    int reconcileExpired();

    /**
     * SPEC-UA-028: the async, independent verification UA-019's own
     * {@code approvalReference} was always missing — a domain-06 approval
     * outcome that arrives DENIED/EXPIRED for a still-ACTIVE grant revokes
     * it immediately; GRANTED is a no-op (already trusted at activation
     * time). Silently does nothing if no ACTIVE grant currently references
     * {@code approvalRequestId} (the underlying request may be unrelated to
     * break-glass entirely, or the grant may already be
     * revoked/expired/never-activated).
     */
    void reconcileApprovalOutcome(ReconcileApprovalOutcomeCommand command);
}
