# Support Console — Testing Strategy

> **Document ID:** LLD-SC-014
> **Domain:** `10-support-console`
> **Status:** Draft
> **Technology baseline:** Vitest + React Testing Library + Playwright (shared with domain 09)

---

## 1. The layered strategy matches domain 09, not repeated here

See `09-employee-portal`'s own `14-testing-strategy` §1 — the same unit/component/contract(MSW)/end-to-end four-layer structure.

## 2. This domain's specific testing focus: the partial-failure matrix of the three-way aggregation

`useAiLog` has 2³=8 real success/failure combinations (excluding "all succeed" and "all fail," the 6 in between are all `PARTIAL` scenarios that need verifying), each must be covered individually:

```text
timeline✓ + tool-request✗ + audit✓  → shows timeline+audit, the tool-execution entry labeled "temporarily unavailable"
timeline✓ + tool-request✓ + audit✗  → shows timeline+tool-request, the approval history labeled "temporarily unavailable"
... the remaining combinations follow the same pattern
timeline✗ + anything                → the whole detail panel is unavailable (timeline is the core dependency, see 10-error-handling §1)
```

## 3. Concurrency/conflict scenarios (corresponds to `09-concurrency-and-idempotency`)

```text
TEST-SC-01: two simulated agents concurrently triage the same ticket; the second must receive a 409 and enter VERSION_CONFLICT, never silently overwriting
TEST-SC-02: an approval request already handled by "another agent"; the current agent clicking grant/deny again must see "already processed," not a generic error
TEST-SC-03: while queue polling is in progress and the agent is editing filter conditions, new data arriving does not reset the filter state
```

## 4. End-to-end scenarios (Playwright, real/docker-compose backend stack)

```text
E2E-SC-01: real login → view the queue → click a ticket → see the full AI processing log → grant a real approval request
           → assert a real GRANTED record appears in policy-approval-governance's database (reusing the exact chain already proven in the 2026-09-01 integration verification)
E2E-SC-02: manually triage → assign → transition status, entirely through real 02-ticket-workflow endpoints, asserting the optimistic-lock If-Match is passed correctly
E2E-SC-03: click "open the full trace in Tempo," asserting the generated deep-link URL contains the correct traceId
```

## 5. Tests explicitly not done (MVP non-goal)

- No testing of the actual Tempo/LangSmith pages' own rendering correctness — those are external systems; this domain only asserts "the deep-link URL was built correctly"
- No performance testing of queue polling under extreme concurrency (hundreds of agents online at once) — the MVP period's agent headcount is limited; performance testing is deferred until real usage growth justifies it
