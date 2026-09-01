# Support Console — Use Cases

> **Document ID:** LLD-SC-004
> **Domain:** `10-support-console`
> **Status:** Draft

---

## UC-SC-01 View the triage queue

**Actor:** A support agent
**Main flow:** Open the console → defaults to the agent's own team's queue → sorted by severity → searchable/filterable
**Dependency:** `02-ticket-workflow`'s real support-queue query capability (already built)

## UC-SC-02 View ticket detail and the AI processing log

**Actor:** A support agent
**Main flow:**
1. Click a queue row → triggers three aggregated requests (ticket timeline / tool-request detail / governance audit records)
2. Renders them, ordered by time, into an `AiLogEntry[]` timeline
3. If a pending approval request exists, the approval card is shown alongside it
**Acceptance criteria:** If any of the three requests fails, the panel enters `PARTIAL` (`03-state-machine` §3.2), never a blanket error

## UC-SC-03 Grant or deny an approval request

**Actor:** A support agent/administrator (needs approval-related permission)
**Precondition:** An `ApprovalRequestView` with `status: "REQUESTED"` exists
**Main flow:** Read the risk level + action description (BI-SC-006) → click grant/deny → wait for backend confirmation → the card becomes a read-only history entry
**Dependency:** `06-policy-approval-governance`'s real grant/deny endpoints — already proven live end-to-end on this same ticket-workflow ↔ governance chain during the 2026-09-01 integration verification

## UC-SC-04 Manually triage/assign/process a ticket (agent acts directly, whether the AI hasn't handled it or failed to)

**Actor:** A support agent
**Main flow:** Connects directly to `02-ticket-workflow`'s real triage/assignment/status-transition endpoints (`TriageTicketController`/`TicketAssignmentController`/`TransitionTicketStatusController`, all already genuinely built)
**Acceptance criteria:** Submissions carry the currently-known version (If-Match); on a backend version conflict the frontend enters `VERSION_CONFLICT` (BI-SC-005), never silently overwriting

## UC-SC-05 View the complete call chain for how a ticket was processed

**Actor:** A support agent/engineering support
**Precondition:** At least one `AiLogEntry` carries a non-null `traceId`
**Main flow:** Click "Open the full trace in Tempo" → builds a Grafana/Tempo deep-link URL from the `traceId` → opens in a new tab (the console does not render the trace-waterfall interaction itself — the in-viewport waterfall is a marketing/preview-level simplification only; real troubleshooting always links out to Tempo's own interface)

## UC-SC-06 View candidate agent-version comparisons (evaluation/canary)

**Actor:** An administrator/engineering lead
**Main flow:** Open the "Observability · Evaluation" page → view `07-evaluation-improvement`'s real version-comparison data (pass rate, regression count, canary percentage) → click "View the full experiment in LangSmith" to link out
**Non-goal (this period):** The console does not provide a button to start a new canary rollout or trigger a rollback here — this period is read-only display only; write operations (adjusting canary percentage) are deferred to a later phase, explicitly a non-goal

## Capabilities still to be added/aggregated

| Use case | Current status | Handling |
|---|---|---|
| UC-SC-01 live queue refresh | REST polling only | MVP polls first (`03-state-machine` §3.1); SSE push is a phase-2+ optimization, explicitly a non-goal |
| UC-SC-02 three-way aggregation | Three real endpoints exist independently, no unified aggregation endpoint | The frontend aggregates the calls itself (reasoning in `05-api-contracts` §3) |
| UC-SC-06 comparison data | Depends on `07-evaluation-improvement`'s real API; exact fields follow that domain's own documentation | Not redefined in this LLD; will be reconciled when built |
