# Employee Portal — Error Handling and Degradation

> **Document ID:** LLD-EP-010
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. A tiered degradation strategy

The employee portal's first principle: **the agent being unavailable does not mean the employee should be stuck.** Every layer of capability failing has a clear, simpler-but-still-usable next layer.

```text
Ideal path: conversational self-service / automatic escalation
    │ domain 03 (conversation-turn capability) unavailable
    ▼
Degradation 1: fall back directly to 02-ticket-workflow's POST /api/v1/tickets to create a ticket
    │ domain 02 also unavailable (extreme case)
    ▼
Degradation 2: show an offline notice + preserve the draft (BI-EP-006), guiding the employee to another IT contact channel
```

## 2. Specific error scenarios

### 2.1 Send-message request times out / returns 5xx
- The frontend does not immediately declare the turn a failure — it retries first (bounded attempts, exponential backoff); if still failing, it enters `AGENT_UNAVAILABLE` (see `03-state-machine`)
- In `AGENT_UNAVAILABLE`, the UI offers a "create a ticket directly" button, following §1's degradation-1 path — the employee doesn't need to know the underlying reason is "the conversation service is down," they just see "converting this to a ticket, a human will follow up"

### 2.2 Attachment upload failure
- A single failed attachment does not block sending other attachments/the text message — the send button's availability only depends on "no attachment currently uploading"; a failed attachment can be removed or retried, and success of all attachments is not mandatory

### 2.3 SSE connection drops
- Auto-reconnects with exponential backoff, up to N attempts before falling back to polling (`GET /api/v1/tickets/{id}` every 30s); state moves from `RECONNECTING` to `STALE` with a "progress may not be current" notice
- On successful reconnect, resumption must use `Last-Event-ID` — it must never "replay the entire history" of status-transition animations from the start

### 2.4 A ProposedAction fails to execute (a real execution error, not a network error)
- Not a network problem — the execution itself genuinely failed (e.g. a tool call really errored). The same actionId is not auto-retried; the agent side should return a new suggestion or escalate. The frontend's only job is to faithfully display the agent's next statement, not decide a retry policy itself

### 2.5 A session/token expires exactly mid-send
- The direct application of BI-EP-006: when a request fails with 401, first write the current input-box content to `draft:{conversationId}`, then prompt re-login; once logged in successfully, auto-restore the draft and allow the user to click send again (does not auto-resend — whether resending is safe depends on whether the previous request actually reached the backend, which the frontend cannot determine, so this is handed back to the user to confirm)

## 3. What is explicitly not done

- No frontend "human-agent fallback chat" that entirely bypasses the ticket system (that would sidestep the whole platform's audit/traceability capabilities).
- Never silently swallow a real error — every degradation path must make it clear to the employee "what happened and what happens next," never just a spinner or a blank page.
