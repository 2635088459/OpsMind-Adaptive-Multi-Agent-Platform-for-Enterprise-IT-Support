# SPEC-SC-015 — Evaluation Comparison Table

> Domain: `10-support-console` | Phase: 06 — Observability Surfaces | Status: Implemented

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
Domain 07's real, already-implemented reads, confirmed by reading `interfaces/rest/router.py` directly (domain 07's actual build is far past the "EI-011~013 next" snapshot an earlier project memory note carried — its own real code is the ground truth, not that stale note): `GET /evaluation/runs/{run_id}`, `GET /evaluation/runs/{run_id}/scores`, `GET /evaluation/runs/{run_id}/regression-report` (404 `NOT_FOUND` when no comparison has run yet — a genuine "never compared" state, not an error). The baseline run to compare against is resolved from the regression report's own `baseline_run_id`, not from `RunResponse.baseline_version` (a version string, not a run id).

A real, first-of-its-kind finding for this domain-10 spec: this is the first Python/FastAPI backend this frontend has ever called. Its Pydantic response models have no `alias_generator` (confirmed by reading `schemas.py` directly and live against a real running instance) — the wire shape is genuinely **snake_case** (`run_id`, `baseline_run_id`, `overall_decision`...), not camelCase like every Java service this app talks to elsewhere. Preserved as-is in this app's own types rather than silently renamed, the same "mirror the real DTO" discipline every other feature here already follows.

## 14. Security
No proxy needed here, unlike SPEC-SC-014's Tempo situation — confirmed by reading `interfaces/security.py` directly: this service's real caller-identity mechanism is a caller-asserted `X-Actor-Id`/`X-Actor-Role` header pair ("a future cross-domain-contracts spec" not yet built, no JWT/bearer validation at all), but the specific reads this spec calls are DELIBERATELY, and by design, open to a caller who asserts no identity at all (05-api-contracts's own default read floor, `EVALUATION_VIEWER`) — live-verified: an anonymous `GET .../scores` call correctly comes back with `evidence: null` on every score (redacted), while the same scores fetched by the authenticated actor who produced them carry a real, populated `evidence` object. This app sends no actor headers at all, relying purely on that documented, already-safe default — it does not assert or need any identity to this service, and introduces no new spoofing surface.

CORS was added for real this session (`CORSMiddleware`, empty/deny by default, `EVALUATION_CORS_ALLOWED_ORIGINS`) — this service had none at all before (every prior consumer was server-to-server).

## 15. Observability
`traceparent` on the fetch, per SPEC-SC-020.

## 16. Error Scenarios
A run that produced zero scores — a genuine empty state, distinct from a fetch failure. A run with no regression report yet (`GET .../regression-report` → 404) renders candidate-only, not an error.

## 17. Acceptance Scenarios
A candidate run compared against a real baseline run renders a table with a real per-dimension regression (candidate average below baseline average) visually flagged — verified against fixtures AND live against a real running instance (a real dataset → 2 runs → real scores → real comparison, driven end-to-end via curl).

## 18. Tests First
Component tests against fixtures matching the real (snake_case) response shapes — full comparison with a real regression, no-baseline-yet, and genuine-empty-scores; a backend unit test for the real CORS behavior (allowed origin, non-allowed origin, deny-by-default with no origins configured).

## 19. Definition of Done
The table renders correctly from fixtures for every real state; live-verified end-to-end against a real running `evaluation-improvement-service` instance — real `RunResponse`/`ScoreResponse`/`RegressionReportResponse` shapes confirmed byte-for-byte, the anonymous-read evidence-redaction behavior confirmed, and the new CORS gate confirmed for both an allowed and a non-allowed origin.
