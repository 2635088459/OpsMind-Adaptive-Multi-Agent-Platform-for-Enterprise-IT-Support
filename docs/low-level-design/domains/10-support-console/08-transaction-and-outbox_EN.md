# Support Console — Transactions and Outbox

> **Document ID:** LLD-SC-008
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. The frontend holds no transaction, relying entirely on the backend's existing atomicity guarantees

Same principle as domain 09 (see its own `08-transaction-and-outbox`). The real atomicity — an approval decision persisted + a ticket status transition triggered + an outbox event — happens entirely inside `06-policy-approval-governance`/`02-ticket-workflow`, and this chain was already genuinely proven to work correctly during the 2026-09-01 integration verification (`project-level-integration-verification` memory).

## 2. Idempotency-Key convention

All side-effecting operations (triage/assign/status-transitions/grant/deny) reuse the platform's existing convention: the frontend generates an `Idempotency-Key`, reused on retry. The only difference from domain 09 is that "retry" here more often comes from an agent's accidental double-click rather than a network retry — but the handling is identical, needing no separate design.

## 3. A point specific to this domain: an approval decision is irreversible; Idempotency-Key prevents duplication, not "changing one's mind"

Once the backend confirms a grant/deny, the agent cannot undo or change that decision by "clicking again" — `Idempotency-Key` only guarantees that a network retry of the same click doesn't produce two real decisions; it provides no undo mechanism. If an agent genuinely clicked the wrong option, that goes through whatever revoke/undo capability `06-policy-approval-governance` itself provides (that domain does have revoke-override-related endpoints; whether they apply to an ordinary approval scenario is that domain's own business rule to decide — not assumed here).
