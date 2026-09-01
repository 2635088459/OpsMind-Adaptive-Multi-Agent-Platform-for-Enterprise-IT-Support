# SPEC-ARO-040 — Acceptance Criteria

Goal: support `Confirm/Decline With Bounded Wait`.

- A low-risk `confirm` genuinely completes a real tool request when the tool finishes within the bounded timeout, returning `"done"`.
- A low-risk `confirm` whose tool does not finish in time returns `"still-processing"` — never a false `"done"`, never an indefinite hang.
- A high-risk `confirm` genuinely creates a real `ApprovalRequest` in `06-policy-approval-governance` and always returns `"awaiting-approval"`.
- `decline` produces zero `tool_requests`/`approval_requests` rows, verified directly against the database.
- Re-confirming or re-declining an already-terminal `actionId` returns the existing real state, triggering no new side effect.
