# Employee Portal — Testing Strategy

> **Document ID:** LLD-EP-014
> **Domain:** `09-employee-portal`
> **Status:** Draft
> **Technology baseline:** Vitest + React Testing Library + Playwright (shared baseline §15, frozen)

---

## 1. Layered testing strategy

```text
Unit tests (Vitest)         → pure functions, hooks logic (no rendering), store state transitions
Component tests (RTL)       → a single component's render/interaction behavior, api/client mocked out
Contract tests (MSW)        → a mock server built against packages/api-contracts' generated types, validating the frontend's own request/parsing logic
End-to-end tests (Playwright) → a real browser, against a real (or docker-compose-provisioned) backend stack
```

## 2. Contract-first: write tests for "pending capabilities" too

Echoing `02-ticket-workflow`'s own roadmap "Contract-first Cross-domain Integration Policy" — even though the conversation-turn endpoints listed in `05-api-contracts` §2 don't exist on the backend yet, the frontend's contract tests **can be written now**: use MSW to mock a deterministic server matching the already-declared contract shape, locking in frontend behavior first; once `03-agent-runtime-orchestration` genuinely builds it, switch to "a compatibility test against the real service," verifying the real response matches the same contract.

## 3. Key scenario checklist

### 3.1 Component-level
- `ProposedActionCard`: `summary` is shown in full, never truncated (BI-EP-007); the button disables immediately after confirmation, preventing repeated clicks
- Attachment upload component: while `uploadStatus` is `uploading`/`failed`, the parent's send button must be disabled (BI-EP-002)
- Ticket status panel: the stepper can only move forward — when SSE delivers an "older" `updatedAt`, the component must never regress (echoes `09-concurrency-and-idempotency` §4)

### 3.2 End-to-end (Playwright, against a real/docker-compose backend stack)
```text
E2E-EP-01: real Keycloak login → send a message → receive a plain-text agent reply
E2E-EP-02: send a message → receive a ProposedAction → confirm → see the execution-complete status
E2E-EP-03: send a message → receive an EscalationNotice → the ticket panel appears, showing real state-machine progress
E2E-EP-04: disconnect/reconnect → the draft is not lost (BI-EP-006) → after re-login, the draft is restored
```

E2E-EP-01 should reuse the real Keycloak Authorization Code + PKCE flow already proven live during this project's 2026-09-01 integration verification (`project-level-integration-verification` memory) — no need to rediscover how to automate the login flow.

### 3.3 Idempotency/concurrency (corresponds to `09-concurrency-and-idempotency`)
- The same message sent twice due to a network retry — assert the mock server only genuinely processes it once (by verifying `Idempotency-Key` reuse)
- The same `actionId` confirmed twice — assert the second request triggers no new execution side effect

## 4. Tests explicitly not done (MVP non-goal)

- No visual regression testing (screenshot diffing) — the MVP period's visual details change frequently, giving this a low ROI; introduce it once the product's visuals have stabilized
- No automated multi-tab concurrency testing — `09-concurrency-and-idempotency` §2 already explains this is a deliberately simplified scenario, not worth building test infrastructure for
- No testing of whether LangSmith/OTel actually reports data correctly — that's `03-agent-runtime-orchestration`'s/`08-observability-platform`'s own testing responsibility; the frontend only asserts that a `traceparent` header is generated and carried, not how the backend handles it
