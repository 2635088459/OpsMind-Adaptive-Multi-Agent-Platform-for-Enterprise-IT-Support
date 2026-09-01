# Employee Portal — API Contracts

> **Document ID:** LLD-EP-005
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. Contracts that already exist for real and can be integrated directly

These were not designed by this LLD — they are already built and were genuinely proven live during the 2026-09-01 project-level integration verification (see `project-level-integration-verification` memory).

### 1.1 Login (01-user-access-authentication)
```text
Real Authorization Code + PKCE, a real Keycloak realm
GET  /oauth2/authorization/opsmind        → 302 to Keycloak
                                           → after callback: Set-Cookie: OPSMIND_SESSION
```
The frontend does not need to implement OIDC details itself — this is a server-side Spring Security session (cookie-based). employee-portal's API calls ride on this already-established session rather than holding/refreshing a JWT itself (details in `11-security-and-authorization`).

### 1.2 Ticket status, read-only (02-ticket-workflow)
```text
GET /api/v1/tickets/{ticketId}
```
The real returned fields were confirmed during integration verification: `ticketId, displayId, status, ...` (see that domain's own `05-api-contracts`). employee-portal consumes this directly and does not redefine its own DTO shape — the types in `packages/api-contracts` should be generated from `02-ticket-workflow`'s OpenAPI, not hand-written.

### 1.3 Ticket creation (02-ticket-workflow, as a fallback path)
```text
POST /api/v1/tickets   {title, description, applicationCode, source: "PORTAL"}
```
Normally a ticket is created via agent-runtime's orchestration (see §2.3); this endpoint serves as the fallback path in `10-error-handling-and-reconciliation` for "the employee must never be stuck even if the agent is entirely unavailable" — direct-connect capability **must** be retained; conversational ticket creation must never be assumed to always be available.

## 2. New endpoints required; this LLD only states the contract shape, not the backend's internal design

The following are all flagged as **pending a new SPEC on `03-agent-runtime-orchestration`**. The shapes below are the minimal contract derived from frontend needs; the final shape is authoritative in that domain's own API contract document.

### 2.1 Create a conversation
```http
POST /api/v1/conversations
```
```json
// request
{}
// response 201
{ "conversationId": "conv_...", "startedAt": "2026-09-01T09:41:00Z" }
```

### 2.2 Send a message
```http
POST /api/v1/conversations/{conversationId}/messages
Idempotency-Key: <uuid>   // reuses the platform's existing convention, see §4
```
```json
// request
{
  "text": "Hi, my Duo verification keeps failing when I log in",
  "attachmentRefs": ["att_8f2c..."]
}
// response 200 — one of three shapes
{ "type": "text", "text": "..." }
{ "type": "proposedAction", "actionId": "act_...", "summary": "...", "riskLevel": "MEDIUM", "requiresConfirmation": true }
{ "type": "escalation", "ticketId": "01a0...", "displayId": "INC-2483", "reason": "...", "assignedTeam": "Field Support" }
```

### 2.3 Confirm/decline a proposal
```http
POST /api/v1/conversations/{conversationId}/actions/{actionId}/confirm
POST /api/v1/conversations/{conversationId}/actions/{actionId}/decline
Idempotency-Key: <uuid>
```
`confirm` triggers internal agent-runtime orchestration (which may in turn call domains 04/05/06, entirely transparent to the frontend). The response shape matches §2.2 (one of three, typically the execution result's `text`, or an `escalation` after a failure).

### 2.4 Live ticket status push
```http
GET /api/v1/tickets/{ticketId}/events
Accept: text/event-stream
Last-Event-ID: <resume-token>   // carried on reconnect
```
```text
event: ticket.status.changed
data: {"ticketId":"...", "status":"IN_PROGRESS", "updatedAt":"..."}
```
Implemented per the shared technology-baseline §4's already-fixed requirement ("SSE supports reconnect, heartbeat, Last-Event-ID"). This is a capability `02-ticket-workflow` **does not have today** (currently REST read only).

## 3. Attachment upload (a shared capability with an as-yet-undecided owner)

```http
POST /api/v1/attachments
Content-Type: multipart/form-data
```
```json
{ "attachmentId": "att_...", "objectRef": "s3://...", "thumbnailUrl": "..." }
```
**Confirmed with the user (2026-09-01):** this does not belong to any existing domain — it is chartered as an independent, platform-level shared capability, pairing with the MinIO/S3-compatible object storage already decided in shared technology-baseline §7, reusable by any future domain (not just employee-portal). Suggested documentation home: `docs/low-level-design/shared/attachments/` (a sibling to `shared/api`, `shared/events`); the concrete contract/validation/virus-scanning hooks are left to that shared capability's own design document — this LLD only states the minimal contract shape employee-portal needs.

## 4. Idempotency-Key convention

Reuses the platform-wide convention already established (the real pattern already in `ticket-workflow-service` and other built services): every POST request with a side effect carries an `Idempotency-Key` header, a client-generated UUID; resubmitting the same key returns the original result instead of re-executing.

## 5. What this domain explicitly does not do

- Never calls `04-memory-knowledge`, `05-tool-integration-gateway`, or `06-policy-approval-governance` directly — all reached through `03-agent-runtime-orchestration`'s own orchestration; the frontend only knows the "conversation" contract layer.
- Never issues or validates a JWT itself — reuses the session mechanism already built by domain 01.
- Never defines the semantics of the ticket state machine — `TicketStatus` values are copied verbatim from `02-ticket-workflow`'s own definition; any new status value must be introduced there first, with the frontend following.
