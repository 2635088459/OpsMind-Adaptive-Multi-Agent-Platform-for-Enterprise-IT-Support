# Support Console — Package Structure and Component Design

> **Document ID:** LLD-SC-013
> **Domain:** `10-support-console`
> **Status:** Draft
> **Technology baseline:** React 19 + Vite 8.x + TypeScript (shared with domain 09, frozen)

---

## 1. Directory structure

```text
apps/support-console/
├── src/
│   ├── app/
│   │   ├── router.tsx            # routes for triage queue / approvals / my tickets / dashboard / observability
│   │   └── providers.tsx
│   ├── features/
│   │   ├── queue/                # queue list, filtering, polling
│   │   ├── ticket-detail/        # AiLogEntry three-way aggregation, approval card
│   │   │   ├── components/
│   │   │   └── hooks/            # useAiLog (concurrently aggregates three requests)
│   │   ├── approvals/            # the pending-approvals inbox view
│   │   ├── observability/        # Trace waterfall preview + evaluation comparison table
│   │   └── auth/
│   ├── components/                # shared presentational layer (shadcn/ui), including SeverityChip, SlaCountdown, etc.
│   ├── api/
│   │   └── generated/             # reuses packages/api-contracts, same generated output shared with employee-portal
│   └── stores/
└── tests/
```

## 2. `useAiLog` — this domain's single most central hook

Corresponds to the aggregation strategy in `05-api-contracts` §3:

```text
useAiLog(ticketId)
  → concurrently calls fetchTicketTimeline / fetchRelatedToolRequests / fetchGovernanceAuditRecords
  → each has its own independent loading/error state (not merged into one big loading/error)
  → successful parts merge and render immediately; failed parts are labeled with exactly which one is missing (the PARTIAL state in 03-state-machine lands here)
```

This hook is called out on its own because it is where this domain's architecture diverges most from domain 09 — that domain's hooks are mostly simple single-request wrappers, whereas this one inherently needs to handle "multiple requests degrading independently."

## 3. Severity/status display mapping, kept in one place

Corresponds to BI-SC-004 (display state always comes from a backend field, never re-judged by the frontend):

```text
components/SeverityChip.tsx    // the single source of truth mapping priority → color/icon; no feature is allowed to write its own copy of this mapping
components/SlaCountdown.tsx    // displays the backend's own slaDeadline; the local "time remaining" is a pure display calculation, never changing the underlying judgment
```

## 4. What is shared with employee-portal

```text
packages/api-contracts    → shared backend-generated types (the only genuinely shared code between the two apps)
```

Deliberately **not** shared beyond the UI component library — `ProposedActionCard` (domain 09) and `ApprovalCard` (domain 10), though conceptually both "approval-related," sit in entirely different interaction contexts (an employee sees "my own request," an agent sees "whether to grant someone else's request"). Forcing a shared abstraction here would make the two sides' evolving needs constrain each other for no real benefit.
