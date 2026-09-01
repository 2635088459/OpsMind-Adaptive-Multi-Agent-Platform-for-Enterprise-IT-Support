# OpsMind Support Console — Implementation Roadmap

> **Document ID:** IMP-SC-000
> **Domain:** `10-support-console`
> **Document Type:** Implementation Roadmap
> **Version:** 1.0
> **Status:** Draft
> **Delivery Method:** Spec-Driven Development + Test-Driven Development + Vertical Slice Delivery
> **Design Baseline:** `docs/low-level-design/domains/10-support-console/`
> **Code Directory:** `apps/support-console/`
> **Feature Spec Directory:** `docs/specs/domains/10-support-console/`
> **Traceability Directory:** `docs/traceability/domains/10-support-console/`

---

# 1. Purpose

Converts the approved Support Console LLD (14 documents) into an executable implementation plan, without redesigning it or duplicating its content.

---

# 2. Review Decisions

## 2.1 Relationship to the employee-portal roadmap

Same 00-09 phase structure, 10 phases total — but the content distribution differs: this domain's greatest engineering complexity sits in Phase 03 (three-way aggregation and partial degradation), not in the login/conversation territory that is domain 09's center of gravity.

## 2.2 Directories
```text
docs/implementation-plans/domains/10-support-console/
docs/specs/domains/10-support-console/
docs/traceability/domains/10-support-console/
apps/support-console/
```

## 2.3 Technology Baseline
Identical to `09-employee-portal` (inherited from the shared technology-baseline, not repeated here).

## 2.4 An important scope advantage

Unlike domain 09, this domain's dependencies are **overwhelmingly already genuinely built** (the confirmed list is already in `01-domain-model` §1). This means Phases 02-05 can connect directly to real endpoints, without domain 09's heavy reliance on contract-first mocks — only Phase 06 (observability/evaluation) and live queue push involve capabilities still pending confirmation/addition.

---

# 3. Design Baseline

```text
01-domain-model
02-business-invariants
03-state-machine
04-use-cases
05-api-contracts
06-event-contracts
07-data-model
08-transaction-and-outbox
09-concurrency-and-idempotency
10-error-handling-and-reconciliation
11-security-and-authorization
12-observability-and-audit
13-package-and-class-design
14-testing-strategy
```

Implementation must not:
- Bypass BI-SC-001~006
- Silently overwrite another agent's changes in a version-conflict scenario (BI-SC-005)
- Let the approval card's grant/deny use an optimistic UI (BI-SC-002)
- Fake a fine-grained authorization on the frontend that `06-policy-approval-governance` does not actually enforce today (this current gap is already honestly recorded in `11-security-and-authorization` §2)

---

# 4. Phase Overview

```text
Phase 00  Engineering Foundation
Phase 01  Login and Session Slice (reuses domain 09's already-verified mechanism)
Phase 02  Triage Queue View Slice
Phase 03  Ticket Detail and AI-Log Aggregation Slice
Phase 04  Approval Decision Slice
Phase 05  Manual Triage/Assign/Transition Slice
Phase 06  Observability and Evaluation Page Slice
Phase 07  Concurrency and Conflict-Handling Hardening
Phase 08  Security and Observability Hardening
Phase 09  Release Readiness
```

---

# 5. Phase 00 — Engineering Foundation

## Objective
`apps/support-console/` Vite + React 19 + TS scaffold, routing skeleton (triage queue/approvals/my tickets/dashboard/observability), CI.

## Deliverables
- Reuses `packages/api-contracts`'s already-established generation pipeline (built in domain 09's Phase 00), adding type generation for domains 05/06/07
- Routing skeleton + an unauthenticated blank home page
- CI: lint + typecheck + `vitest run`

## Exit Criteria
Same structure as `09-employee-portal` Phase 00's exit criteria, not repeated here.

Detailed plan: `phase-00-engineering-foundation_EN.md`

---

# 6. Phase 01 — Login and Session Slice

## Objective
Reuse `01-user-access-authentication`'s real mechanism, verifying the real scopes of an agent role (`ticket:triage`/`ticket:assign`, etc.) are carried correctly.

## Feature Specs
```text
SPEC-SC-001-oidc-login-redirect
SPEC-SC-002-role-scope-verification
```

## Why This Phase Is Faster Than Domain 09's
The login mechanism itself has already been verified once by domain 09's Phase 01 (the same Keycloak realm, the same PKCE flow); this phase's focus is verifying whether **an agent account with different scopes** can correctly access agent-only endpoints, not rediscovering login itself.

---

# 7. Phase 02 — Triage Queue View Slice

## Objective
```text
An agent logs in
→ sees their team's queue
→ sorted by severity, with SLA-imminent items highlighted
```

## Feature Specs
```text
SPEC-SC-003-queue-list
SPEC-SC-004-severity-and-sla-display
SPEC-SC-005-queue-polling
```

## Key Requirements
- Connects directly to `02-ticket-workflow`'s real queue query endpoint, no mock needed
- Severity/SLA display strictly follows backend fields (BI-SC-004)

---

# 8. Phase 03 — Ticket Detail and AI-Log Aggregation Slice

## Objective
```text
Click a queue row
→ concurrently fetch timeline + tool-request detail + governance audit
→ merge into one AiLogEntry timeline
→ any single failure enters PARTIAL rather than a blanket error
```

## Why This Is the Domain's Core Phase
`useAiLog` (`13-package-and-class-design` §2) is this domain's most architecturally complex part, and the direct implementation of the core product positioning: "an agent can review what the AI already did."

## Feature Specs
```text
SPEC-SC-006-ai-log-aggregation
SPEC-SC-007-partial-degradation-states
```

## Key Requirements
- All 2³ success/failure combinations have test coverage (`14-testing-strategy` §2)
- Each of the three requests has its own independent loading/error state, never merged into one generic loading indicator

---

# 9. Phase 04 — Approval Decision Slice

## Objective
```text
The ticket detail panel shows a pending approval request
→ the agent grants/denies it
→ the card becomes read-only history (irreversible)
```

## Feature Specs
```text
SPEC-SC-008-approval-card
SPEC-SC-009-grant-deny-action
```

## Key Requirements
- Grant/deny uses a non-optimistic UI (BI-SC-002), waiting for real backend confirmation
- Directly reuses `06-policy-approval-governance`'s grant/deny endpoints already proven live during the 2026-09-01 integration verification — another example in this phase of "the backend capability is ready, only the frontend needs building"

---

# 10. Phase 05 — Manual Triage/Assign/Transition Slice

## Objective
Direct manual intervention capability for when the AI hasn't handled a ticket, failed to, or a human adjustment is needed.

## Feature Specs
```text
SPEC-SC-010-manual-triage
SPEC-SC-011-manual-assign
SPEC-SC-012-manual-status-transition
SPEC-SC-013-version-conflict-handling
```

## Key Requirements
- Every operation carries an If-Match version number
- A version conflict enters `VERSION_CONFLICT` (BI-SC-005), never silently overwriting — this phase's acceptance focus is "is the conflict handling genuinely visible and actionable," not "does the operation succeed" (the latter is already simple absent concurrency)

---

# 11. Phase 06 — Observability and Evaluation Page Slice

## Objective
```text
A Trace waterfall preview + "open in Tempo" external link
An evaluation/canary comparison table + "view in LangSmith" external link
```

## Feature Specs
```text
SPEC-SC-014-trace-waterfall-preview
SPEC-SC-015-evaluation-comparison-table
```

## Key Requirements
- Connects to `07-evaluation-improvement`'s real version-comparison data (exact fields follow that domain's own documentation)
- Dedicated tests for the Tempo deep-link URL's correctness (`14-testing-strategy` §4, E2E-SC-03)
- This phase is explicitly read-only display; it provides no write-operation entry point into any observability system

---

# 12. Phase 07 — Concurrency and Conflict-Handling Hardening

## Objective
Systematically verify every multi-agent collaboration scenario defined in `09-concurrency-and-idempotency`, rather than testing them piecemeal across phases.

## Feature Specs
```text
SPEC-SC-016-concurrent-triage-conflict
SPEC-SC-017-concurrent-approval-conflict
```

---

# 13. Phase 08 — Security and Observability Hardening

## Objective
Same structure as `09-employee-portal` Phase 08, content corresponding to this domain's own `11-security-and-authorization`/`12-observability-and-audit`.

## Feature Specs
```text
SPEC-SC-018-scope-hardening
SPEC-SC-019-partial-authorization-visibility
SPEC-SC-020-trace-propagation-coverage
```

---

# 14. Phase 09 — Release Readiness

## Objective
E2E-SC-01~03 all passing, basic performance/accessibility checks, a release-gate checklist.

## Why Last
Depends on every prior phase, and E2E-SC-01 directly reuses the real ticket-workflow ↔ policy-approval-governance chain already proven feasible during the 2026-09-01 integration verification — the final validation of the whole architectural assumption.

---

# 15-20. Standard Structures, Traceability, Quality Gates

Identical template to `09-employee-portal`'s roadmap §16-19 (Standard Phase Plan Structure / Standard Feature Spec Structure / Traceability / Cross-phase Quality Gates) — not reformatted here; reuse those sections directly during implementation.

---

# 21. MVP Boundary

```text
Phase 00 → 01 → 02 → 03 → 04 → 05
```

Phases 06, 07, 08, 09 may be classified as production-oriented extensions.

The demo should at minimum show:
```text
Login → view the queue → open a ticket to see the AI processing log → grant a real approval request
```

---

# 22. Immediate Next Steps

```text
1. Review this roadmap
2. Write phase-00-engineering-foundation_EN.md
3. Build the Phase 00 engineering foundation
4. Write SPEC-SC-001-oidc-login-redirect_EN.md
5. Enter Phase 01's RED stage
```
