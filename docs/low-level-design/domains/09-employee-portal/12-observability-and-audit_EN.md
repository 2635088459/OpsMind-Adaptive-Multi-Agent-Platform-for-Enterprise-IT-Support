# Employee Portal — Observability and Audit

> **Document ID:** LLD-EP-012
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. The frontend's role in the two-layer observability system

Follows the two-layer split already fixed in shared technology-baseline §10/§11:

```text
OpenTelemetry = engineering observability (cross-service call chains)
LangSmith     = agent-semantic observability (prompt/completion/tool trajectories)
```

The frontend **only participates in the first layer**, and only as a "starting point" — it does not do what only the backend should do:

- Every API call generates/propagates a `trace_id` (W3C Trace Context header, `traceparent`), letting every backend service the request touches (domain 03's orchestration chain, possibly running through domains 04/05/06) stitch together the full call chain for that one employee request — exactly the starting point of the real Trace waterfall diagram proven live in the `Agent Observability` mockup (see the real INC-2481 trace in `project-level-integration-verification` memory).
- The frontend **never** creates its own spans reported to Tempo — it is only the carrier of the header; real spans are produced by each backend service.
- The frontend **never** touches LangSmith — per the boundary already stated in §5 of `11-security-and-authorization`, that line is entirely server-side.

## 2. The client's own error reporting (frontend-only, not cross-service tracing)

- Uncaught frontend exceptions (render errors, promise rejections) are reported to a lightweight error-collection endpoint (e.g. a Sentry-compatible protocol) — a pure frontend engineering practice, not part of the platform's two-layer OTel/LangSmith system. In the MVP period this can be just `console.error` + local instrumentation; genuinely integrating an external service is phase 2+ work, explicitly listed as a non-goal.

## 3. Audit: the frontend produces no audit records; it is only the trigger source for audit events

Real audit records (like `AuditRecordEntry`) are written by each backend domain while processing a real business operation (`02-ticket-workflow`/`06-policy-approval-governance` have both already implemented this for real). The frontend's only responsibility is: **every operation that triggers a backend audit carries a real actor identity** (from the JWT `sub`) and a `correlationId` — never missing, never fabricated — so that the backend audit record's `actorId`/`traceId` correctly matches what the employee actually did in the portal.

## 4. Frontend-specific metrics worth monitoring (for future dashboards; not mandatory for the MVP)

```text
Time from first message send to receiving the agent's response (frontend-perceived end-to-end latency)
Attachment upload success rate
SSE reconnect frequency
Frequency of session-expiry interrupting a send (measures how often BI-EP-006 is actually triggered)
```

None of these are a deliverable of this phase — they are listed here so that `13-package-and-class-design`/`14-testing-strategy` can keep future instrumentation hook placement in mind, avoiding a later refactor.
