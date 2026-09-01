# Employee Portal — Domain Model

> **Document ID:** LLD-EP-001
> **Domain:** `09-employee-portal`
> **Status:** Draft
> **Technology Baseline:** `docs/low-level-design/shared/technology-baseline/README_EN.md` (React 19 + Vite)
> **Product Direction Source:** `frontend-product-vision` memory (visual mockups confirmed with the user, 2026-09-01)

---

## 1. What this domain actually "owns"

Unlike domains 01-08, employee-portal **owns no persistent business state at all**. It is a pure frontend application — the real business facts (ticket status, approval decisions, knowledge retrieval results) always live in their owning backend domain's own database:

```text
Ticket truth              → 02-ticket-workflow (ticket-workflow-service)
Approval truth             → 06-policy-approval-governance
Knowledge/memory truth      → 04-memory-knowledge
Agent conversation-turn truth → 03-agent-runtime-orchestration (new capability required, see §3)
```

employee-portal only owns: **the client-side view model of a conversation** (ephemeral UI state like Conversation/Message) and **browser local storage** (drafts, offline cache). This is not a shortcut simplification — it is a deliberate architectural decision: any attempt to let the frontend "keep its own copy" of ticket state would create a real inconsistency between frontend and backend facts (BI-EP-004/005, see `02-business-invariants`).

## 2. Core Concepts

### Conversation
A single support conversation between an employee and OpsMind. A client-side aggregate, not a backend-persisted entity.

```text
Conversation
  conversationId: string          // Derived from the agent-runtime side's conversation/workflow-instance
  ticketId: string | null         // Backfilled once escalated/a ticket is created
  messages: Message[]
  status: "active" | "escalated" | "closed"
  startedAt: datetime
```

### Message
```text
Message
  messageId: string
  role: "user" | "agent" | "system"
  text: string
  attachments: Attachment[]
  proposedAction: ProposedAction | null   // Only agent messages may carry this
  escalation: EscalationNotice | null     // Only agent messages may carry this
  createdAt: datetime
```

### Attachment
```text
Attachment
  attachmentId: string
  filename: string
  mimeType: string
  sizeBytes: number
  objectRef: string        // Object storage reference (MinIO/S3-compatible, see shared baseline §7)
  thumbnailUrl: string | null
  uploadStatus: "uploading" | "ready" | "failed"
```

### ProposedAction (a fix the agent proposes)
When the agent determines it has permission to resolve directly, it first explains what it will do and waits for confirmation (matches the "Confirm, please handle it" button in the visual mockup).

```text
ProposedAction
  actionId: string
  summary: string              // A plain-language explanation, e.g. "re-pair your Duo device"
  riskLevel: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"   // Same name/values as domain 06's RiskLevel, kept consistent across the stack
  requiresConfirmation: boolean
  status: "proposed" | "confirmed" | "declined" | "executing" | "done" | "failed"
```

### EscalationNotice
Attached when the agent determines it has no permission/capability to act, carrying the real, already-created ticketId.

```text
EscalationNotice
  ticketId: string
  reason: string          // "This is physical hardware damage, needs on-site repair"
  assignedTeam: string | null
```

### TicketStatusView (read-only ticket-progress projection)
Not an entity of this domain — a **read-only projection** over the real Ticket aggregate owned by `02-ticket-workflow`, fetched via API/SSE, never inferred locally.

```text
TicketStatusView
  ticketId: string
  displayId: string        // e.g. "INC-2483"
  status: TicketStatus     // Same name/values as ticket-workflow's real state machine
  category: string
  assignedTeam: string | null
  slaDeadline: datetime | null
  updatedAt: datetime
```

### UserSession
Produced by `01-user-access-authentication`'s real Keycloak OIDC flow; this domain only consumes it, never issues it.

```text
UserSession
  subject: string           // JWT sub
  displayName: string
  scopes: string[]
  accessTokenExpiresAt: datetime
```

## 3. A critical, honestly-stated cross-domain dependency gap

The conversational interaction described in the visual mockup — "employee sends a message → agent analyzes context + knowledge base → proposes a fix or escalates" — is **not owned by any backend domain today**:

- `03-agent-runtime-orchestration`'s existing `WorkflowInstance` model is designed to orchestrate the automated processing of an *already-existing* ticket, not a synchronous, back-and-forth conversational turn.
- No `POST /conversations/{id}/messages`-style synchronous conversation endpoint exists anywhere.

**This is not a gap employee-portal itself can fill** — advancing a conversation turn (calling the LLM, retrieving the knowledge base, deciding whether it has permission to act, deciding whether approval is needed, deciding whether to create a ticket) is fundamentally business orchestration logic, and per this project's own domain-boundary principles, it can only belong to `03-agent-runtime-orchestration` — the frontend must not assemble it itself.

**Conclusion:** `03-agent-runtime-orchestration` needs a new batch of SPEC-ARO-0xx specs (conversation-turn related). employee-portal's `05-api-contracts` assumes this contract exists, but this LLD does not overreach into designing 03's internal implementation — it only states the contract shape the frontend needs (see `05-api-contracts` §2). This is the single most important "known dependency, not this domain's own gap" note in this entire LLD set.

## 4. Relationship to existing domains

```text
employee-portal (09, pure frontend)
    │ OIDC login
    ▼
user-access-authentication (01) — already built, already verified live
    │
    │ conversation turn (new contract, see §3)
    ▼
agent-runtime-orchestration (03) — orchestration logic exists, conversation endpoint pending
    │                    │
    │ knowledge retrieval  │ create a ticket when no permission
    ▼                    ▼
memory-knowledge (04)   ticket-workflow (02) — already built, already verified live
    │ execute when permitted  │
    ▼                    │
tool-integration-gateway (05)   │ approval for high risk
                          ▼
                   policy-approval-governance (06) — already built, already verified live
```

employee-portal only calls domains 01, 02 (ticket status, read-only), and 03 (once built) directly; 04/05/06 are always reached indirectly through 03's own orchestration — the frontend never connects to them directly.
