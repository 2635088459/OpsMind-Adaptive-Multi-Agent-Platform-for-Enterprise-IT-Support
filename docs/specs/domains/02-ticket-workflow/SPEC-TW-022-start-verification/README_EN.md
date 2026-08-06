# SPEC-TW-022 — Start Verification

## 1. Goal

Start an independent verification attempt from the Phase 06 tool result reference. The ticket is `VERIFYING`, and the verification attempt is bound to the current ticket, workflow, resolution cycle, tool result, and attempt number.

## 2. Scope

Included:

- `POST /internal/v1/tickets/{ticketId}/verification/start`
- create verification attempt;
- store verificationId, toolResultId, attemptNumber;
- publish `ticket.verification-started.v1`;
- timeline, audit, outbox, idempotency.

Excluded: Verification Agent execution, success/failure result, resolution.

## 3. Core Rules

- Ticket status is `VERIFYING`;
- tool result belongs to the current workflow/cycle/action;
- no two active verification attempts for the same tool result;
- attemptNumber is monotonic;
- clients cannot spoof verification result.
