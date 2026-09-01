# Support Console — Concurrency and Idempotency

> **Document ID:** LLD-SC-009
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. Core scenario: multiple agents collaborating on the same batch of tickets

This is where this domain's concurrency concerns fundamentally differ from domain 09's — domain 09 is "the same person, multiple devices"; domain 10 is "multiple real humans genuinely working at the same time." The handling principle is already set in BI-SC-005: **never silently overwrite, always make conflict visible.**

## 2. Optimistic-lock version conflicts (triage/assign/status-transitions)

```text
Agent A fetches the ticket, version=3
Agent B fetches it at the same time, also version=3
Agent A submits a triage first (If-Match: 3) → succeeds, backend version → 4
Agent B submits an assignment (If-Match: 3) → rejected by the backend, 409
  → the frontend enters VERSION_CONFLICT
  → refetches the latest ticket state (version=4, already A's triage result)
  → shows Agent B: "This ticket was already triaged by {A's name}, do you still want to proceed with assignment?"
  → Agent B decides: abandon / resubmit based on the latest state
```

No auto-retry, no auto-merge of the two people's edits — this scenario needs genuine human judgment.

## 3. Concurrent approval decisions (two authorized people both trying to grant/deny the same request)

The backend `06-policy-approval-governance` already guarantees only one decision takes effect (via idempotency + its own state machine — that domain's own invariant). Frontend responsibility: the second agent to click, on receiving an "already processed" response, is shown directly "this request was already {granted/denied} by {whom} at {when}," not a generic error.

## 4. Interaction between queue polling and local filter state

When a poll refreshes queue data, the refresh must not interrupt what the agent is currently doing if they're mid-typing a filter/sort setting (an `EDITING`-class transient UI state) — new data is merged in the background first, and the filtering/sorting view recomputes transparently against the new data, without resetting whatever filter the agent already set.

## 5. Concurrency controls explicitly not built (MVP non-goal)

- No real-time "someone is currently viewing this ticket" presence indicator (collaborative awareness, e.g. "Jamie is viewing this ticket") — a common but non-core feature of real collaboration tools, left to a later phase.
- No "claiming" mechanism for approval requests (when multiple authorized people race to handle the same request, whoever sees it first gets it) — in the MVP, multiple people may all see and act on the same pending request; this relies on the backend idempotency in §3 as the safety net, with no optimistic claim-lock introduced on the frontend.
