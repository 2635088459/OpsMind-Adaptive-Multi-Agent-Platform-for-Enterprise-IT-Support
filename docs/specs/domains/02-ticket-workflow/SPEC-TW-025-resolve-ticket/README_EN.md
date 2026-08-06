# SPEC-TW-025 — Resolve Ticket with Verification

## 1. Goal

Complete resolution from trusted verification evidence stored by `SPEC-TW-023`, moving the ticket from `VERIFYING` to `RESOLVED` and fully persisting the resolution cycle.

This is not a duplicate of Phase 03 `SPEC-TW-010`; this SPEC requires verification evidence and applies to the automated/tool-execution path.

## 2. Scope

Included:

- `POST /internal/v1/tickets/{ticketId}/verified-resolution`
- `VERIFYING -> RESOLVED`
- resolution code/summary/by/at;
- verification evidence reference;
- resolution cycle completion;
- `ticket.resolved-with-verification.v1`.

Excluded: close, auto-close, reopen.

## 3. Core Rules

- Ticket status is `VERIFYING`;
- verification evidence is trusted, current, and successful;
- evidence matches current workflow/cycle/attempt;
- resolution summary/code are still required;
- `RESOLVED` is not `CLOSED`.
