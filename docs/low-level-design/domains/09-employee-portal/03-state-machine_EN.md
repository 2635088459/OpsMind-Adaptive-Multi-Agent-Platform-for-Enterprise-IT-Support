# Employee Portal — Interaction State Machines

> **Document ID:** LLD-EP-003
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## Note

A backend domain's state machine describes the lifecycle of a **domain object** (a Ticket going from NEW to CLOSED). What follows describes the lifecycle of **interactions themselves** — how a single conversation turn, an attachment, and a login session each flow. This is unique to a frontend domain; the backend's 14-document template has no direct counterpart, but this document keeps the same numbered position for structural symmetry between the two domain sets.

## 3.1 Conversation Turn State Machine

```text
IDLE
  → (user sends a message) → SENDING
SENDING
  → (request reached the server) → AWAITING_AGENT      // the "analyzing" thinking state
  → (network failure) → SEND_FAILED → (retry) → SENDING
AWAITING_AGENT
  → (agent returns plain text) → IDLE
  → (agent returns a ProposedAction) → AWAITING_CONFIRMATION
  → (agent returns an EscalationNotice) → ESCALATED
  → (timeout/5xx) → AGENT_UNAVAILABLE
AWAITING_CONFIRMATION
  → (user clicks confirm) → ACTION_EXECUTING
  → (user clicks decline) → IDLE
ACTION_EXECUTING
  → (execution succeeds) → IDLE   // agent message appends a "done" status card
  → (execution fails) → ACTION_FAILED → (agent auto-degrades to ESCALATED, or allows the user to retry)
ESCALATED
  → (ticket created, status panel begins rendering) → IDLE   // the conversation returns to an input-ready state; the ticket panel has its own independent lifecycle
AGENT_UNAVAILABLE
  → (see the degradation path in 10-error-handling-and-reconciliation) → ESCALATED (via the direct fallback path against ticket-workflow)
```

Key rule: during `AWAITING_CONFIRMATION` and `ACTION_EXECUTING`, the input box can still accept text (asking the next question is not blocked), but **the same ProposedAction can never be confirmed twice** — the button disables immediately on click and waits for the server response or a timeout.

## 3.2 Attachment State Machine

```text
(none selected)
  → (user selects a file) → VALIDATING     // frontend validates size/type first
VALIDATING
  → (passes) → UPLOADING
  → (fails) → REJECTED (reason shown, never enters upload)
UPLOADING
  → (succeeds) → READY
  → (fails) → FAILED → (user may retry) → UPLOADING
READY
  → (sent with a message) → attached to a Message, leaves this state machine's management
  → (user removes it) → (back to none selected)
```

Maps to BI-EP-002: only attachments in the `READY` state may appear in a message about to be sent.

## 3.3 Session / Login State Machine

```text
UNAUTHENTICATED
  → (initiates OIDC login) → LOGIN_IN_PROGRESS
LOGIN_IN_PROGRESS
  → (real Authorization Code + PKCE callback succeeds) → AUTHENTICATED
  → (fails) → UNAUTHENTICATED (error shown)
AUTHENTICATED
  → (access token nears expiry) → TOKEN_REFRESHING
  → (revoked / refresh fails) → SESSION_EXPIRED
TOKEN_REFRESHING
  → (succeeds silently) → AUTHENTICATED
  → (fails) → SESSION_EXPIRED
SESSION_EXPIRED
  → (triggers BI-EP-006: draft saved locally) → UNAUTHENTICATED
  → (re-login succeeds, draft restored) → AUTHENTICATED
```

This state machine reuses `01-user-access-authentication`'s real Authorization Code + PKCE flow, already verified live (see `project-level-integration-verification` memory) — this domain does not redesign the login mechanism, only describes how the frontend reacts to its state changes.

## 3.4 Ticket Status Panel (independent of the conversation turn)

The ticket panel is not this domain's own state machine — it only renders `02-ticket-workflow`'s real state machine (NEW → TRIAGED → ASSIGNED → IN_PROGRESS → WAITING_FOR_APPROVAL/WAITING_FOR_USER → RESOLVED → CLOSED) via a read-only projection. The only state the frontend maintains on its own is **connection state**:

```text
CONNECTING → LIVE (SSE connected, or polling started)
LIVE → RECONNECTING (connection dropped, resumes via Last-Event-ID, see shared baseline §4)
RECONNECTING → LIVE / STALE (repeated retries fail; shows "progress may not be current, tap to refresh")
```
