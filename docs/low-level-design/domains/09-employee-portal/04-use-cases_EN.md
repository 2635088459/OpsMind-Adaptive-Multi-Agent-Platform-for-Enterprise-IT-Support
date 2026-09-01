# Employee Portal — Use Cases

> **Document ID:** LLD-EP-004
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## UC-EP-01 Start a new conversation

**Actor:** A logged-in employee
**Precondition:** `UserSession` is `AUTHENTICATED`
**Main flow:**
1. The employee opens the portal and sees an empty conversation area (or their most recent unfinished conversation, see UC-EP-06)
2. Selects/lets the agent auto-infer a service category (access/permissions, login, computer, printer, VPN, other — display grouping only, not a required form field)
3. Types the first message
**Produces:** A new `Conversation` (conversationId issued by the backend, see `05-api-contracts` §2.1)

## UC-EP-02 Send a message (optionally with attachments)

**Actor:** A logged-in employee
**Precondition:** The turn state machine is `IDLE`
**Main flow:**
1. The employee types text, optionally adding photos/files (triggers the §3.2 attachment state machine)
2. Clicks send → enters `SENDING` → `AWAITING_AGENT`
3. The agent returns one of: plain text / `ProposedAction` / `EscalationNotice`
**Acceptance criteria:**
- The send button is disabled while any attachment is not yet ready (BI-EP-002)
- Clicking send repeatedly does not produce two duplicate messages (see `09-concurrency-and-idempotency`)

## UC-EP-03 Confirm or decline a self-service fix

**Actor:** A logged-in employee
**Precondition:** The turn state machine is `AWAITING_CONFIRMATION`
**Main flow:**
1. The employee reads the proposal's explanation (BI-EP-007: the full explanation must be visible, never truncated)
2. Clicks "Confirm, please handle it" → enters `ACTION_EXECUTING` → the agent executes → a status card updates step by step (as in the mockup's "✓ Old device pairing removed")
3. Once execution completes, the agent asks a confirming follow-up ("Did that fix it?")
**Alternate flow:** The employee clicks "Not now" → returns directly to `IDLE`, triggering no backend side effect
**Error flow:** Execution fails → `ACTION_FAILED` → the agent automatically proposes escalation (entering UC-EP-04's ticket-creation path), rather than leaving the employee stuck on a failed state

## UC-EP-04 The agent determines it lacks permission, auto-creates a ticket, and notifies the employee

**Actor:** The system (agent-runtime orchestration), employee receives passively
**Precondition:** The agent determines the current request exceeds its own permission/capability boundary
**Main flow:**
1. The agent's message carries an `EscalationNotice` (with a real ticketId, reason, assigned team)
2. The frontend inserts a prominent notice into the conversation stream (as in the mockup: "Ticket INC-2483 created, see the panel on the right for progress")
3. The ticket status panel appears/updates, beginning to show real state-machine progress (see UC-EP-05)
**Acceptance criteria:** The ticketId must be genuinely issued by `02-ticket-workflow` — the frontend is never allowed to generate a placeholder ID and swap it later

## UC-EP-05 View ticket progress

**Actor:** A logged-in employee
**Precondition:** The current conversation is linked to a real ticketId
**Main flow:**
1. The panel continuously receives `02-ticket-workflow`'s status changes via SSE (or polling fallback, see `10-error-handling-and-reconciliation`)
2. The state-machine steps highlight the current stage (NEW → TRIAGED → IN_PROGRESS → RESOLVED, mapped one-to-one to the backend's real `TicketStatus`, with no simplified frontend-only mapping)
3. Upon reaching `RESOLVED`, the panel prompts the employee to confirm resolution (corresponds to `SPEC-TW-026-confirm-resolution`, a real, already-built backend capability)
**Acceptance criteria:** The displayed status is always the server's latest value; after a reconnect, the panel must never "regress" to an older state (Last-Event-ID guarantees ordering, see shared baseline §4)

## UC-EP-06 Return to the portal, resume the last conversation

**Actor:** A logged-in employee
**Precondition:** The employee has an unclosed `Conversation` (or one already escalated but the ticket is not yet CLOSED)
**Main flow:**
1. On opening the portal, the most recent active/escalated conversation is shown first, rather than a blank page
2. If already escalated, the ticket panel renders directly at its current real status (no replay of the entire history animation)
**Non-goal:** This period does not support "a list of multiple parallel conversations" (the mockup only shows a single current conversation) — a multi-conversation history list is left to a later phase

## Backend capabilities required by these use cases, but not implemented by this domain

| Use case | Required new backend capability | Owning domain |
|---|---|---|
| UC-EP-01/02/03 | Conversation-turn endpoints (create conversation, send message, confirm a proposal) | 03-agent-runtime-orchestration |
| UC-EP-02 (attachments) | Attachment / object-storage upload endpoint | To be determined (likely a new shared capability, not naturally owned by any existing domain) |
| UC-EP-05 | An SSE push endpoint for ticket status changes | 02-ticket-workflow (currently REST read only, no push) |

This table is one of the most important outputs in this LLD set: it honestly states what's still missing on the backend for the employee portal to genuinely work, rather than pretending these capabilities already exist.
