# SPEC-ARO-040 — Domain Rules

Goal: support `Confirm/Decline With Bounded Wait`.

- `AWAITING_USER_CONFIRMATION` is a new, first-class `AgentTaskState`; it is not modeled as a special case of an existing state.
- Which branch (tool dispatch vs. governance approval) a `confirm` takes is decided purely by the `ProposedAction.riskLevel` already produced in SPEC-ARO-039 — this spec never re-derives or overrides that risk classification.
- A task in `AWAITING_USER_CONFIRMATION` cannot be claimed/completed by the existing async worker path — the same `_require_active_claim()`-style guard already used for `WAITING_TOOL` (SPEC-ARO-019) applies here by the same reasoning.
