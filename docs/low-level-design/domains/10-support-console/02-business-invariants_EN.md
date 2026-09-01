# Support Console — Business Invariants

> **Document ID:** LLD-SC-002
> **Domain:** `10-support-console`
> **Status:** Draft

---

## BI-SC-001 — A support agent can only see tickets in their own queues/teams (unless granted cross-queue access)

**Owner: backend enforcement** (`02-ticket-workflow`'s support-queue authorization is already genuinely built). Frontend responsibility: queue queries always carry a real JWT; the frontend never constructs a "fetch every queue" call and filters client-side.

## BI-SC-002 — Grant/deny decisions are never simulated locally on the frontend

**Owner: frontend adjudication + backend enforcement.** After clicking "Grant"/"Deny," the UI must wait for a real response from `06-policy-approval-governance` before switching the card's state to "Granted"/"Denied" — an optimistic UI that flips state first and rolls back on error is never allowed (approval is an irreversible, high-risk action; an optimistic update here is more dangerous than a correct loading state).

## BI-SC-003 — AiLogEntry is an aggregated view; it always labels its source and never poses as a single source of truth

`01-domain-model` §2 already states that `AiLogEntry` is aggregated across three backend domains. When rendered, every entry must be traceable to a `sourceDomain` + `sourceRef`; the UI allows an agent to click through to that domain's own raw record. The frontend must not treat the aggregated result as a new, independent "source of truth" cached for use in other decisions.

## BI-SC-004 — Queue-list severity/SLA display always comes from backend fields, never re-judged on the frontend

`Ticket.priority` and the SLA deadline are already computed by the backend; the frontend only maps them to a display form (e.g. mapping `HIGH` to a warm-colored chip) — it is never allowed to use a locally computed "time remaining until deadline" to override the backend's own judgment (avoiding a mismatch between what an agent sees as urgent and the backend's real assessment due to clock drift).

## BI-SC-005 — Concurrent operations by multiple agents on the same ticket must make the second operator aware of the conflict, never silently overwrite it

Unlike domain 09's "last write wins" simplification (there is no equivalent BI-EP rule — that was a deliberate omission there) — support-console faces **multiple real humans genuinely collaborating**, where a silent overwrite means real work is genuinely lost (e.g. two agents triaging the same ticket at once, one's judgment silently overwritten by the other's). The frontend must propagate the backend's real optimistic-lock version conflict (which genuinely exists in `02-ticket-workflow`), surfacing "this ticket was already changed by someone else" as a visible error state to the agent, never swallowing it and retrying.

## BI-SC-006 — The risk level/action description on an approval card is never truncated for UI space reasons

The same principle as domain 09's BI-EP-007, stricter here: this is the sole basis on which a real human makes an irreversible grant/deny decision — any truncation here is a genuine security risk, not just a UX flaw.
