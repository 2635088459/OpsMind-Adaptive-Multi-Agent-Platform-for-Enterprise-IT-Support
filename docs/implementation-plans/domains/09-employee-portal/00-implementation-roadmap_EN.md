# OpsMind Employee Portal — Implementation Roadmap

> **Document ID:** IMP-EP-000
> **Domain:** `09-employee-portal`
> **Document Type:** Implementation Roadmap
> **Version:** 1.0
> **Status:** Draft
> **Delivery Method:** Spec-Driven Development + Test-Driven Development + Vertical Slice Delivery
> **Design Baseline:** `docs/low-level-design/domains/09-employee-portal/`
> **Code Directory:** `apps/employee-portal/`
> **Feature Spec Directory:** `docs/specs/domains/09-employee-portal/`
> **Traceability Directory:** `docs/traceability/domains/09-employee-portal/`

---

# 1. Purpose

This document converts the approved Employee Portal LLD (14 documents) into an executable, production-oriented implementation plan.

It answers:

```text
Now that the design intent is clear,
what gets built first, why in this order,
and what must be complete before the next phase begins?
```

It does not redesign employee-portal, and does not duplicate the content of the 14 LLD documents.

---

# 2. Review Decisions

## 2.1 Phase Structure

Phase 00–09, 10 phases total. One fewer than the backend's `02-ticket-workflow` (Phase 00-10). Reason: the frontend has no equivalent to two large backend phases — "security/audit hardening" and "chaos/reconciliation" — as standalone efforts; this work is much smaller in scope on the frontend and is folded into Phase 08 (security/observability hardening) and Phase 07 (resilience/error-handling hardening) instead of being chartered separately.

## 2.2 Directories

```text
docs/implementation-plans/domains/09-employee-portal/
docs/specs/domains/09-employee-portal/
docs/traceability/domains/09-employee-portal/
apps/employee-portal/
```

## 2.3 Technology Baseline

Frozen (inherited from the shared technology-baseline, not re-selected):

```text
TypeScript
React 19
Vite 8.x
Node.js 24 LTS
pnpm
React Router
TanStack Query
Zustand
React Hook Form + Zod
Tailwind CSS + shadcn/ui
Vitest + React Testing Library + Playwright
SSE (not WebSocket)
```

## 2.4 Phase 00 Scope

Phase 00 establishes engineering foundations (scaffolding, routing skeleton, CI, a minimal verification of the real Keycloak login redirect) — it implements no conversation/ticket business behavior.

## 2.5 Cross-domain Dependency Strategy

`01-domain-model`/`04-use-cases`/`05-api-contracts` have already flagged three backend capabilities not yet built:

```text
1. Conversation-turn endpoints    → 03-agent-runtime-orchestration (charter pending)
2. Ticket-status SSE push          → 02-ticket-workflow (new addition needed)
3. Attachment-upload shared capability → a new, independent shared capability (charter pending)
```

Following the **Contract-first Cross-domain Integration Policy** already proven effective in `02-ticket-workflow`'s own roadmap (see §9): rather than waiting for these three backend capabilities to actually exist, this domain locks in frontend behavior against the already-declared contract shapes and a deterministic mock (MSW), then switches to a compatibility test once the real service is ready. This means Phases 02-05 can proceed in parallel with backend work, not blocked by it.

---

# 3. Design Baseline

The following Employee Portal LLD documents are complete:

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

- Bypass any of BI-EP-001~007
- Locally fabricate/infer ticket status on the frontend (BI-EP-004/005)
- Let the frontend's own judgment fully replace the backend's re-check of a ProposedAction's execution precondition (BI-EP-003)
- Silently discard a user's typed-but-unsent draft (BI-EP-006)
- Hand-write backend DTO types anywhere outside `packages/api-contracts`

---

# 4. Phase Overview

```text
Phase 00  Engineering Foundation
Phase 01  Login and Session Slice
Phase 02  Conversation Core Slice (send a message → receive a text reply)
Phase 03  Self-service Action Confirmation Slice (ProposedAction)
Phase 04  Evidence File Slice (attachment upload)
Phase 05  Escalation and Ticket Progress Panel Slice
Phase 06  Resolution Confirmation and Reopen Slice
Phase 07  Resilience and Error-Handling Hardening
Phase 08  Security and Observability Hardening
Phase 09  Release Readiness
```

All phases belong to `09-employee-portal`; code lives in `apps/employee-portal/`.

---

# 5. Minimum Cross-cutting Baseline for Every Slice

Beginning with Phase 01, every Feature Spec checks:

```text
Real OIDC session state
Idempotency-Key (for side-effecting requests like sending a message/confirming an action)
Error handling (user-visible feedback for network failures/timeouts/5xx)
Trace propagation (the traceparent header)
Unit/component/end-to-end tests
```

Phase 08 does not repair code that completely omitted security/observability in an earlier phase — Phase 08 focuses on wrap-up and hardening.

---

# 6. Phase 00 — Engineering Foundation

## Objective
Build the engineering and test foundation needed for SDD/TDD, without implementing any business behavior.

## Main Design References
```text
13-package-and-class-design
14-testing-strategy
technology-baseline
```

## Deliverables
- `apps/employee-portal/` Vite + React 19 + TypeScript scaffold
- A minimal working generation pipeline for `packages/api-contracts` (first generating types for the two already-built domains, 01-user-access-authentication and 02-ticket-workflow)
- A routing skeleton (`app/router.tsx`), with an unauthenticated blank home page
- CI: lint + typecheck + `vitest run`
- Basic Playwright configuration pointed at the real `infrastructure/docker-compose/full-platform.yml` stack (can be just a "the page opens" smoke test for now)

## Exit Criteria
```text
pnpm build passes
pnpm test passes (even with few tests)
CI runs green
No Conversation/Message/Ticket business code exists
```

Detailed plan: `phase-00-engineering-foundation_EN.md`

---

# 7. Phase 01 — Login and Session Slice

## Objective
Fully implement UC-EP-01's login precondition: a real Keycloak Authorization Code + PKCE login redirect → callback → `AUTHENTICATED` session state.

## Why Now
Every later phase needs a real, logged-in session; and this exact flow was already proven live against the same Keycloak realm during the 2026-09-01 project-level integration verification (`project-level-integration-verification` memory) — this reuses an already-verified contract rather than discovering it from scratch.

## Feature Specs
```text
SPEC-EP-001-oidc-login-redirect
SPEC-EP-002-session-state-machine
SPEC-EP-003-draft-preservation-on-expiry
```

## Exit Criteria
- A real browser redirect to Keycloak, entering real test credentials, returns to the portal in `AUTHENTICATED`
- Silent refresh near token expiry works; a failed refresh correctly enters `SESSION_EXPIRED` (`03-state-machine` §3.3)
- A draft interrupted by session expiry is written to `localStorage` (BI-EP-006) and restored after re-login

---

# 8. Phase 02 — Conversation Core Slice

## Objective
```text
A logged-in employee
→ sends the first message
→ receives a plain-text agent reply
```

## Why Now
This is the employee portal's entry-point experience, and it validates the `Conversation`/`Message` domain model, the turn state machine, and whether the Contract-first strategy from §2.5 is itself workable.

## Feature Specs
```text
SPEC-EP-004-create-conversation
SPEC-EP-005-send-message
SPEC-EP-006-turn-state-machine
```

## Key Requirements
- Uses MSW to mock the contracts declared in `05-api-contracts` §2.1/§2.2; once `03-agent-runtime-orchestration`'s real endpoint is ready, add compatibility tests rather than rewriting frontend logic
- Repeatedly clicking send does not produce two duplicate messages (`09-concurrency-and-idempotency` §1)
- Every turn state-machine transition has a matching component test

---

# 9. Phase 03 — Self-service Action Confirmation Slice

## Objective
```text
The agent's reply carries a ProposedAction
→ the employee confirms or declines
→ once confirmed, progress is shown through to completion
```

## Feature Specs
```text
SPEC-EP-007-proposed-action-card
SPEC-EP-008-confirm-action
SPEC-EP-009-decline-action
```

## Key Requirements
- `ProposedActionCard`'s explanation text must never be truncated (BI-EP-007)
- The button disables immediately on confirm; the same `actionId` cannot re-trigger real execution (`09-concurrency-and-idempotency` §3)
- On execution failure, the next step shown is decided by the agent itself (a new suggestion, or escalation) — the frontend never retries on its own

---

# 10. Phase 04 — Evidence File Slice

## Objective
Let the employee attach photos/files to a message as evidence for the agent's analysis.

## Why Here
Earlier than the "escalation" slice, because both scenarios in the visual mockup (the MFA issue, the cracked screen) depend on the attachment capability, and the attachment state machine (`03-state-machine` §3.2) is reasonably self-contained and can be validated independently.

## Feature Specs
```text
SPEC-EP-010-attachment-upload
SPEC-EP-011-attachment-validation
```

## Key Requirements
- Depends on the new, independent shared attachment capability (charter pending, see §2.5) — same contract-first approach: mock against the declared contract first, switch over once the real shared capability is ready
- A single failed attachment does not block sending other attachments/text (`10-error-handling-and-reconciliation` §2.2)
- Only `READY`-state attachments may appear in a sent message (BI-EP-002)

---

# 11. Phase 05 — Escalation and Ticket Progress Panel Slice

## Objective
```text
The agent determines it lacks permission
→ auto-creates a real ticket
→ the ticket status panel appears and continuously shows real progress
```

## Why Now
This is the only scenario in the employee portal that genuinely touches real `02-ticket-workflow` data, and it is the most differentiated experience in the visual mockup.

## Feature Specs
```text
SPEC-EP-012-escalation-notice
SPEC-EP-013-ticket-status-panel
SPEC-EP-014-ticket-status-sse
SPEC-EP-015-resume-conversation
```

## Key Requirements
- The `ticketId` carried by `EscalationNotice` must be genuinely issued by `02-ticket-workflow` (acceptance criterion of `04-use-cases` UC-EP-04)
- The SSE endpoint is also contract-first: `02-ticket-workflow` currently only has REST reads; mock against the shape declared in `06-event-contracts` §2.1 first, then wire up the real push endpoint once ready
- Reconnects use `Last-Event-ID`, never regressing the state-machine step highlight (`09-concurrency-and-idempotency` §4)
- UC-EP-06: reopening the portal shows the most recent active/escalated conversation directly, not a blank page

---

# 12. Phase 06 — Resolution Confirmation and Reopen Slice

## Objective
Once a ticket reaches `RESOLVED`, the employee confirms resolution in the portal, or flags it as unresolved to trigger reopening.

## Why Now
Closes the loop on the visual mockup's "progress panel"; and the backend capabilities it depends on (`SPEC-TW-026-confirm-resolution`, reopen-related endpoints) are already real, built capabilities in `02-ticket-workflow` — no new contract needs to be waited on.

## Feature Specs
```text
SPEC-EP-016-confirm-resolution
SPEC-EP-017-reopen-from-portal
```

## Key Requirements
- Connects directly to real endpoints, no MSW mock stage needed — this is the first phase in this domain where the entire chain is already-built capability, and can serve as a milestone validating the whole architectural assumption

---

# 13. Phase 07 — Resilience and Error-Handling Hardening

## Objective
Land the full degradation matrix defined in `10-error-handling-and-reconciliation` as a coherent whole, rather than handling it piecemeal across phases.

## Scope
```text
Agent unavailable → direct fallback to ticket-workflow to create a ticket (degradation path 1)
Fully offline → preserve the draft + a clear notice (degradation path 2)
SSE reconnect + polling fallback
Attachment upload retry
Cross-cutting scenarios of session expiry combined with a mid-request failure
```

## Feature Specs
```text
SPEC-EP-018-agent-unavailable-fallback
SPEC-EP-019-offline-degradation
SPEC-EP-020-sse-reconnect-hardening
```

---

# 14. Phase 08 — Security and Observability Hardening

## Objective
Complete and harden the security/observability capabilities already introduced in earlier phases.

## Scope
```text
Verifying the new scopes (conversations:create/message/confirm-action) are correctly enforced
Full frontend-side attachment security validation
An XSS-protection audit (full coverage of the agent-text rendering path)
traceparent propagation covering every API call
Client-side error reporting integration (optional; the MVP can fall back to local instrumentation)
```

## Feature Specs
```text
SPEC-EP-021-scope-hardening
SPEC-EP-022-xss-audit
SPEC-EP-023-trace-propagation-coverage
```

---

# 15. Phase 09 — Release Readiness

## Objective
Prove the full golden path can run end-to-end against a real (or docker-compose-provisioned) backend stack.

## Scope
```text
E2E-EP-01~04 (defined in 14-testing-strategy §3.2) all passing
Basic accessibility checks (keyboard navigation, basic screen-reader usability)
Performance budget (first paint, frontend-perceived latency from sending a message to receiving a reply)
A release-gate checklist
```

## Why Last
Depends on every prior phase's real capabilities being in place, and requires `03-agent-runtime-orchestration`/`02-ticket-workflow`'s corresponding new backend capabilities to be genuinely built (no longer mocked).

---

# 16. Standard Phase Plan Structure

Every phase plan contains:

```text
1. Objective
2. Why This Phase Now
3. Design References
4. Included Feature Specs
5. Scope
6. Non-goals
7. Architecture Decisions Applied
8. TDD Execution Order
9. Implementation Tasks
10. Test Plan
11. Deliverables
12. Risks
13. Exit Criteria
14. Traceability Update
```

---

# 17. Standard Feature Spec Structure

```text
1. Spec Identity
2. Objective
3. Design References
4. Actor
5. Scope
6. Non-goals
7. Preconditions
8. Input
9. Detailed Behavior
10. Interaction State Transition
11. Business Invariants
12. Idempotency Strategy
13. Consumed/Depended-on Contracts
14. Security
15. Observability
16. Error Scenarios
17. Acceptance Scenarios
18. Tests First
19. Definition of Done
```

---

# 18. Traceability

Maintain:
```text
docs/traceability/domains/09-employee-portal/traceability-matrix.yaml
```

Example:
```yaml
SPEC-EP-005:
  phase: Phase-02
  design:
    use_cases: [UC-EP-02]
    api: ["POST /api/v1/conversations/{id}/messages"]
    invariants: [BI-EP-002]
  implementation:
    components: [MessageComposer, useSendMessage]
  tests:
    - SendMessage.test.tsx
    - useSendMessage.test.ts
    - E2E-EP-01
```

---

# 19. Cross-phase Quality Gates

Every phase requires:

```text
Reviewed Feature Specs
Tests submitted before or with implementation
Coverage of critical invariants
No unapproved breaking API change
Real backend integration tests (for already-built capabilities) or clearly labeled mocks (for pending capabilities)
No sensitive data in logs/traces
Updated traceability matrix
Updated README and run instructions
```

---

# 20. MVP Boundary

```text
Phase 00 → 01 → 02 → 03 → 05 → 06
```

Phase 04 (attachments), 07, 08, 09 may be classified as:
```text
Production-oriented extension
```

The demo should at minimum show:
```text
Login → send a message → the agent analyzes and proposes a fix → confirm → execution completes
      → another message → the agent determines it lacks permission → auto-escalates to a ticket → the progress panel
```

---

# 21. Immediate Next Steps

```text
1. Review this roadmap
2. Write phase-00-engineering-foundation_EN.md
3. Confirm the directory and technology baseline
4. Build the Phase 00 engineering foundation
5. Satisfy Phase 00's exit criteria
6. Write SPEC-EP-001-oidc-login-redirect_EN.md
7. Enter Phase 01's RED stage
```
