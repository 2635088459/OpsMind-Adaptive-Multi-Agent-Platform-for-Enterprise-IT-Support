# Support Console — Domain Model

> **Document ID:** LLD-SC-001
> **Domain:** `10-support-console`
> **Status:** Draft
> **Technology Baseline:** `docs/low-level-design/shared/technology-baseline/README_EN.md` (React 19 + Vite, shared with domain 09)

---

## 1. Key differences from employee-portal

Domain 09 has one large cross-domain gap (no backend anywhere owns the conversation-turn capability). Domain 10 is **in much better shape**: most of what a support agent/admin needs is already genuinely built on the backend — this is a purely "aggregate and display the already-existing truth" frontend, not one that needs a large new chunk of backend business capability first. Real, confirmed supporting endpoints:

```text
GET /api/v1/tickets/{ticketId}/timeline          → 02-ticket-workflow (real)
GET /api/v1/governance-audit-records             → 06-policy-approval-governance (real)
GET /api/v1/tool-requests/{toolRequestId}         → 05-tool-integration-gateway (real)
POST /api/v1/approval-requests/{id}:grant / :deny → 06-policy-approval-governance (real, proven live 2026-09-01)
Support-queue query capability                    → 02-ticket-workflow (real, QuerySupportQueueApplicationService)
```

What's still missing (detailed in §5): queue-level real-time push, and a support-staff-readable aggregated view of evaluation/canary data. The gap is far smaller than domain 09's.

## 2. Core Concepts

### QueueView (triage queue view)
A presentation-layer wrapper over `02-ticket-workflow`'s real support-queue query capability — not an independently persisted entity.

```text
QueueView
  queueId: string
  teamName: string
  items: QueueItem[]
```

```text
QueueItem
  ticketId, displayId, title
  severity: "CRITICAL" | "HIGH" | "MEDIUM"   // a frontend display tier, mapped from Ticket.priority
  assignee: { type: "agent" | "human"; name: string } | null
  slaDeadline: datetime | null
  status: TicketStatus       // same name/values as domain 02
```

### TicketDetailView (ticket detail + AI operation log)
```text
TicketDetailView
  ticket: TicketStatusView          // reuses the shape already defined in domain 09
  requester: { name, department }
  aiLog: AiLogEntry[]               // see below
  pendingApproval: ApprovalRequestView | null
```

### AiLogEntry (an agent-processing log entry)
Aggregated from `02-ticket-workflow`'s timeline + `05-tool-integration-gateway`'s tool-request detail + `06-policy-approval-governance`'s audit records — **a view aggregating across three backend domains**, not a data structure native to any single one.

```text
AiLogEntry
  step: string              // a plain-language summary, e.g. "matched a standard knowledge-base runbook"
  sourceDomain: "ticket-workflow" | "tool-integration-gateway" | "policy-approval-governance"
  sourceRef: string          // the real record's id, for click-through to detail
  status: "done" | "pending" | "failed"
  occurredAt: datetime
  traceId: string | null     // used for the Observability page's Tempo deep link, see 04-use-cases
```

### ApprovalRequestView (the approval card)
Maps directly to `06-policy-approval-governance`'s real `ApprovalRequest` — fields copied verbatim, semantics not redefined.

```text
ApprovalRequestView
  approvalRequestId, ticketId
  requestedAction: string
  riskLevel: RiskLevel        // same name/values as the backend: LOW/MEDIUM/HIGH/CRITICAL
  scopeNote: string           // e.g. "expires automatically after 24 hours"
  status: "REQUESTED" | "GRANTED" | "DENIED" | ...  // same values as the backend's real state machine
```

### OperatorSession (support-staff login session)
Structurally the same as domain 09's `UserSession`, but carries different scopes (`ticket:triage`/`ticket:assign`/approval-related, etc., all already-real backend scopes).

## 3. Relationship to domain 08 (observability-platform)

`08-observability-platform` is pure infrastructure (it has no business service of its own, see its memory record) — support-console's "Observability · Evaluation" page never calls a non-existent "domain 08 service API." Instead:

- For the Trace waterfall: build a Tempo/Grafana deep-link URL using `AiLogEntry.traceId`, linking out directly — the frontend does not render the complete trace itself (that rendering is Grafana/Tempo's own UI's responsibility)
- For the evaluation/canary comparison table: calls `07-evaluation-improvement`'s real EvaluationRun query capability (that domain is already built; exact fields follow that domain's own API contract, not redefined here)

## 4. Types shared with employee-portal, not redefined

`TicketStatusView`, `RiskLevel`, `TicketStatus` are needed by both apps — they live in `packages/api-contracts` (shared by both apps); domains 09/10 are not each allowed to maintain their own, potentially drifting, copy.

## 5. Backend capabilities still missing, needing new additions

| Capability | Current status | Owner |
|---|---|---|
| Queue-level real-time push (the support-staff UI auto-refreshing when a new ticket enters the queue/priority changes) | REST query only, no push | 02-ticket-workflow (new addition) |
| The `AiLogEntry` aggregation itself | The data genuinely exists in three separate domains, but no single endpoint stitches them into one timeline | To be decided: either support-console aggregates via its own frontend calls, or a new BFF layer is introduced. This LLD adopts the former (frontend aggregation) — reasoning in `05-api-contracts` §3 |
