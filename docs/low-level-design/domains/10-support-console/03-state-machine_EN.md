# Support Console — Interaction State Machines

> **Document ID:** LLD-SC-003
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 3.1 Queue View Loading/Refresh State Machine

```text
LOADING
  → (first fetch succeeds) → LIVE_POLLING       // MVP: polling, not SSE (see the new dependency noted in §5)
LIVE_POLLING
  → (a poll fails) → DEGRADED (shows a "data may not be current" notice, continuing to use the last successful result)
  → (the next poll succeeds) → LIVE_POLLING
```

## 3.2 Ticket Detail Panel

```text
UNSELECTED (no ticket selected, a blank prompt on the right side of the queue)
  → (a queue row is clicked) → LOADING_DETAIL
LOADING_DETAIL
  → (all three aggregated requests succeed: ticket timeline + tool-request detail + governance audit) → READY
  → (any of the three fails) → PARTIAL (shows the successful parts + a clear notice of which part failed to load, rather than a blanket error/blank state)
```

`PARTIAL` is a state unique to this domain, necessitated by `AiLogEntry` being a three-way aggregation (`01-domain-model` BI-SC-003) — an agent must never lose visibility into two successfully-loaded parts just because a third backend domain happens to be temporarily unavailable.

## 3.3 Approval Action State Machine

```text
PENDING (awaiting the agent's decision)
  → (clicks grant) → SUBMITTING_GRANT
  → (clicks deny) → SUBMITTING_DENY
SUBMITTING_GRANT / SUBMITTING_DENY
  → (backend confirms) → DECIDED (irreversible; the card becomes a read-only record of the historical decision)
  → (backend rejects/conflicts, e.g. already handled by another agent) → CONFLICT (shows "this request has already been processed, current status: {X}," refreshed to the latest real state)
```

Corresponds to BI-SC-002: `DECIDED` can only come from a backend confirmation, never from an optimistic frontend flip.

## 3.4 Optimistic-lock Conflicts (ticket-editing operations, e.g. triage/assignment)

```text
EDITING
  → (submitted) → SUBMITTING
SUBMITTING
  → (succeeds) → SAVED
  → (version conflict, 409) → VERSION_CONFLICT (BI-SC-005: a clear "changed by someone else" notice, shows their latest version, the agent decides whether to overwrite or abandon)
```

`VERSION_CONFLICT` is never auto-retried or auto-merged — this state requires genuine human judgment, and has no counterpart at all in domain 09 (a self-service employee scenario has no such multi-person collaboration conflict).
