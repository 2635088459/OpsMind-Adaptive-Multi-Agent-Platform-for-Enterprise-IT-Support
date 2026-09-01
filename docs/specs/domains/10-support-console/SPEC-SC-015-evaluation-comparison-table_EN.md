# SPEC-SC-015 — Evaluation Comparison Table

> Domain: `10-support-console` | Phase: 06 — Observability Surfaces | Status: Spec Planning

## 1. Spec Identity
`SPEC-SC-015`, implements the LangSmith/evaluation half of `UC-SC-07`, matching the mockup's evaluation/canary comparison table section.

## 2. Objective
Embed a comparison table of evaluation runs (baseline vs. canary/candidate agent behavior) sourced from `07-evaluation-improvement`'s real run/scoring model (fully closed through phase-02, EI-011~013 next per project memory).

## 3. Design References
`01-domain-model` §"EvaluationComparisonTable"; `04-use-cases` UC-SC-07; the Agent Observability mockup (LangSmith-analogue section); domain 07's real run/scoring domain model.

## 4. Actor
An admin (primarily) reviewing agent-quality trends, or an agent curious why a particular response pattern occurred.

## 5. Scope
Fetching evaluation run data for a relevant scope (e.g., runs touching the same dataset/test-case as this ticket's category, if such correlation exists) and rendering as a comparison table (metric columns, baseline vs. candidate rows).

## 6. Non-goals
Any new evaluation/scoring logic (domain 07 owns this entirely) — this is a read-only consumer.

## 7. Preconditions
Domain 07 has runs available to query (may be empty for a brand-new deployment — an empty state is a valid, expected rendering).

## 8. Input
An optional scope filter (dataset, run ID range).

## 9. Detailed Behavior
Fetch run/scoring data from domain 07's real read endpoints and render a table with metric columns; highlight regressions (candidate scoring worse than baseline) using the same semantic-color convention as SPEC-SC-004's severity treatment.

## 10. Interaction State Transition
N/A — a read-only view.

## 11. Business Invariants
The table must reflect real scoring data — no fabricated or illustrative-only numbers once wired to production.

## 12. Idempotency Strategy
N/A — a `GET`.

## 13. Consumed/Depended-on Contracts
Domain 07's real run-read/scoring-read endpoints (exact paths to be confirmed against domain 07's own API contract doc — this is the first domain-10 spec touching domain 07, so the contract shape is not yet cross-referenced elsewhere in this domain's LLD).

## 14. Security
Requires whatever read scope domain 07 defines for evaluation-run visibility (to be confirmed).

## 15. Observability
`traceparent` on the fetch, per SPEC-SC-020.

## 16. Error Scenarios
No runs available for the given scope — a genuine empty state, distinct from a fetch failure.

## 17. Acceptance Scenarios
A dataset with 2 runs (one baseline, one candidate showing a regression on one metric) renders a table with that regression visually flagged.

## 18. Tests First
A component test against a fixture matching domain 07's real run/scoring response shape, covering both the regression-highlight and empty-state cases.

## 19. Definition of Done
The table renders correctly from fixtures for both populated and empty states; a compatibility check against domain 07's real endpoint is added once its exact contract is confirmed.
