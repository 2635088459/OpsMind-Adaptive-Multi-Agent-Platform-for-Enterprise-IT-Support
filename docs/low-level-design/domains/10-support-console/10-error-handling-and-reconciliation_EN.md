# Support Console — Error Handling and Degradation

> **Document ID:** LLD-SC-010
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. Partial failure of the three-way aggregation (the AiLogEntry scenario)

`03-state-machine` §3.2 already defines the `PARTIAL` state. Specific handling:

```text
ticket timeline fails    → the whole detail panel is unavailable (this is the core data; nothing meaningful can be shown without it)
tool-request detail fails → the AI log entry for that item shows "tool-execution detail temporarily unavailable," other entries render as normal
governance audit fails    → the approval card shows "approval history temporarily unavailable," but if there is pendingApproval data (from an independent approval-requests request) the grant/deny buttons still render normally
```

The three requests degrade independently — a failure on one path never blocks the other two. This is a class of error-handling complexity unique to this domain, arising from its aggregation architecture (`05-api-contracts` §3), with no equivalent in domain 09.

## 2. Queue polling failure

Enters `DEGRADED` (`03-state-machine` §3.1), continuing to show the last successful result with a clear "data may not be current" notice, not clearing the queue list into a blank page — while an agent is working, a blank page is more harmful than "slightly stale data."

## 3. Approval-action submission failure

- Network failure/timeout: the agent may retry (same `Idempotency-Key`), never auto-retried (approval is high-risk; whether to retry should be a human decision, especially when it's unclear whether the previous request actually reached the backend)
- 409 conflict (already processed): see `09-concurrency-and-idempotency` §3, showing the latest real state directly

## 4. Version conflict (triage/assign/status-transitions)

See `09-concurrency-and-idempotency` §2 — this is not a "failure" in the error sense, it's a normal business scenario requiring agent judgment; the UI presents it in a neutral style, not a red-error style.

## 5. Degradation explicitly not built

- No fully offline support-agent working mode designed for "the backend is entirely unavailable" — domain 09's employee fallback path (direct ticket creation) has no reasonable equivalent here: an agent's own work fundamentally depends on real, current backend data — offline work has no meaning in this context, and the right behavior is a clear "the system is temporarily unavailable, please try again later," not pretending to keep working.
