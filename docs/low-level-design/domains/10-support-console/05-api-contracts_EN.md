# Support Console — API Contracts

> **Document ID:** LLD-SC-005
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. Contracts that already exist for real, connected directly

```text
GET  /api/v1/support-queues/{queueId}/tickets      → 02-ticket-workflow
GET  /api/v1/tickets/{ticketId}                    → 02-ticket-workflow
GET  /api/v1/tickets/{ticketId}/timeline           → 02-ticket-workflow
POST /api/v1/tickets/{ticketId}/triage             → 02-ticket-workflow
POST /api/v1/tickets/{ticketId}/assign             → 02-ticket-workflow
POST /api/v1/tickets/{ticketId}/status-transitions → 02-ticket-workflow
GET  /api/v1/tool-requests/{toolRequestId}          → 05-tool-integration-gateway
GET  /api/v1/governance-audit-records               → 06-policy-approval-governance
GET  /api/v1/approval-requests/{approvalRequestId}  → 06-policy-approval-governance
POST /api/v1/approval-requests/{id}:grant / :deny   → 06-policy-approval-governance (proven live 2026-09-01)
```

Field shapes follow each domain's own `05-api-contracts`; this document only states support-console's calling conventions, without redefining them.

## 2. Version-control convention (If-Match)

All ticket-mutating operations (triage/assign/status-transitions) reuse `02-ticket-workflow`'s existing optimistic-lock convention: the request carries `If-Match: <currently known version>`; on a version mismatch the backend returns 409, and the frontend enters `VERSION_CONFLICT` (BI-SC-005). support-console **introduces no version-control mechanism of its own**, fully following the existing convention.

## 3. AiLogEntry aggregation: frontend aggregation, not a new BFF endpoint

Of the two options raised in `01-domain-model` §5, this LLD chooses **concurrent frontend calls to the three real endpoints, stitched together locally**, for these reasons:

- All three endpoints already exist; three concurrent (not sequential) requests are fast enough performance-wise, and don't require introducing a new aggregation layer with added operational complexity
- A new BFF aggregation endpoint would itself need a new service/deployment unit, conflicting with the principle that "support-console should be a pure consumer, introducing no new business-logic ownership" (echoing the same boundary principle in domain 09's own `01-domain-model`)
- If the frontend's field-stitching logic is later found too complex, or cross-request caching optimization is needed, a dedicated aggregation layer can be reconsidered — this is a deliberately deferred decision, not one that was never considered

```typescript
// pseudocode illustrating the aggregation approach, not the final implementation
const [timeline, toolRequests, auditRecords] = await Promise.all([
  fetchTicketTimeline(ticketId),
  fetchRelatedToolRequests(ticketId),
  fetchGovernanceAuditRecords(ticketId),
]);
const aiLog = mergeIntoTimeline(timeline, toolRequests, auditRecords); // a pure frontend function, sorted by occurredAt
```

## 4. Live queue updates (MVP: polling)

```http
GET /api/v1/support-queues/{queueId}/tickets?since={lastPolledAt}
```
Polled every 15-30 seconds (the exact interval left to real load-testing during phase implementation). SSE push is explicitly a phase-2+ non-goal for this period — not implemented now, and this LLD does not pretend its contract shape is already decided — building it for real needs `02-ticket-workflow` to add a new capability, following the same contract-first approach used in domain 09.

## 5. Two kinds of external links on the Observability page

```text
Trace deep link: https://{grafana-host}/explore?...traceID={AiLogEntry.traceId}
LangSmith deep link: returned directly by 07-evaluation-improvement's API response as a complete external URL; the frontend does not construct LangSmith's own URL format itself (avoiding needing a frontend change whenever LangSmith's own URL scheme changes)
```
