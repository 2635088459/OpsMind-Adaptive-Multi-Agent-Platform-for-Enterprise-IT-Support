# Employee Portal — Package Structure and Component Design

> **Document ID:** LLD-EP-013
> **Domain:** `09-employee-portal`
> **Status:** Draft
> **Technology baseline:** React 19 + Vite 8.x + TypeScript (shared baseline §4, frozen)

---

## 1. Directory structure

```text
apps/employee-portal/
├── src/
│   ├── app/                     # app entry, routing, provider composition
│   │   ├── router.tsx
│   │   └── providers.tsx        # QueryClientProvider / ThemeProvider, etc.
│   ├── features/
│   │   ├── conversation/        # conversation core: Message, Composer, ProposedActionCard
│   │   │   ├── components/
│   │   │   ├── hooks/           # useSendMessage, useConfirmAction (TanStack Query)
│   │   │   └── store.ts         # Zustand: turnState, other ephemeral interaction state
│   │   ├── ticket-status/       # the ticket-progress panel
│   │   │   ├── components/
│   │   │   └── hooks/           # useTicketEvents (SSE subscription)
│   │   └── auth/                # login redirect, session state, draft restoration (BI-EP-006)
│   ├── components/               # shared presentational components across features (built on shadcn/ui)
│   ├── api/
│   │   ├── client.ts             # unified request wrapper (carries traceparent, Idempotency-Key)
│   │   └── generated/            # TS types generated from packages/api-contracts, never hand-written
│   ├── lib/                      # general utilities (local-storage wrappers, date formatting, etc.)
│   └── styles/
├── tests/
│   ├── unit/
│   ├── component/
│   └── e2e/                      # Playwright
└── vite.config.ts
```

## 2. Layering rules (the frontend counterpart of the backend's "controllers hold no business rules")

```text
features/*/components   →  render only + forward actions to hooks, never call api/client directly
features/*/hooks        →  the only place allowed to call api/client, wrapped with TanStack Query
features/*/store.ts     →  only for "ephemeral UI state shared across components"; server data always flows through TanStack Query's own cache, never duplicated into Zustand
api/client.ts           →  the only place that knows the real backend URLs/header conventions
```

This layering shares the same spirit as the backend's "domain does not depend on infrastructure" — separating "how the screen is drawn" from "where the data comes from," to make testing and swapping easier.

## 3. The real purpose `packages/api-contracts` finds here

This is the first time this project has given this previously-empty package a real purpose: it generates shared TypeScript types from each backend domain's OpenAPI (Java services) / Pydantic models (Python services), consumed jointly by `employee-portal` and the future `support-console`, avoiding both sides hand-writing and drifting their own DTO definitions. The generation script itself is outside this LLD's scope (belonging to `packages/api-contracts`'s own engineering implementation) — this document only states "this domain depends on it, does not reinvent it."

## 4. How the state-management choices are actually applied

- **Server data** (ticket status, conversation history): TanStack Query, with built-in cache/retry/invalidation policy — naturally fitting the "the frontend does not keep its own copy of authoritative state" principle (BI-EP-004/005).
- **Pure client interaction state** (the turn state machine, attachment upload progress): Zustand — lightweight, and this ephemeral state does not need Query's caching semantics.
- **Forms** (if an explicit form is ever needed, e.g. describing an attachment): React Hook Form + Zod, with validation rules sharing the same source as `packages/api-contracts`'s types.

## 5. A concrete component-design example: ProposedActionCard

Corresponds to the "Confirm, please handle it" card in the mockup:

```text
<ProposedActionCard action={proposedAction}>
  props: action: ProposedAction
  internal state: none (pure display + forwards click events)
  behavior:
    onConfirm → calls useConfirmAction(actionId) from features/conversation/hooks
    onDecline → calls useDeclineAction(actionId)
  rendering rule: action.summary must be shown in full — CSS truncation/ellipsis is never permitted (the component-level implementation of BI-EP-007)
</ProposedActionCard>
```
