# SPEC-XIT-001 — broader cross-service integration smokes

Status: implemented 2026-09-09
Owner: platform / cross-cutting
Related: SPEC-XREL-001, SPEC-XOBS-001, `scripts/tool-execution-loop-smoke.sh`, `scripts/approval-loop-smoke.sh`

## 1. Problem

The only cross-service assertions that run in CI are:

- `tool-execution-loop-smoke.sh` — confirm → agent-runtime → tool-gateway → Keycloak → email → workflow resumed
- `approval-loop-smoke.sh` — a synthetic `opsmind.events` message → event-relay → agent-runtime/tool-gateway HTTP seam
- the two Playwright suites (browser flows, static reasoning)

Two whole business chains have **no** end-to-end assertion, only seed scripts that
*drive* them without checking the result:

1. **The ticket lifecycle** — escalation / creation → triage → assign → IN_PROGRESS →
   resolve → confirm → CLOSED. `seed-demo-tickets.sh` walks it but asserts nothing
   beyond HTTP 2xx per call, and stops different tickets at different states.
2. **Approval grant / deny** — a governance `ApprovalRequest` created, then granted
   (separation-of-duties: decider ≠ requester) and denied, and the resulting
   `approval.granted.v1` / `approval.denied.v1` reaching the bus.

Also: `conversation-multimodal-smoke.sh` (employee sends a chat message with an image,
agent-runtime fetches the bytes from attachment-service) exists but is **not wired into
`frontend-e2e.yml`** — so the multimodal path has no CI gate at all.

## 2. Scope

- `scripts/ticket-lifecycle-smoke.sh` — one ticket, driven through **every** lifecycle
  transition via the real ticket-workflow API (optimistic `If-Match` version +
  `X-Correlation-Id` on every mutation), asserting the ticket `status` after each step
  and the final `CLOSED`. Then drains the ticket outbox and asserts the
  `ticket.resolved.v1` event was published **and** that the event-relay (SPEC-XREL-001
  follow-up) delivered it to memory-knowledge's candidate-memory pipeline — so this
  smoke doubles as the gate for "the knowledge base grows from real resolutions".
- `scripts/approval-decision-smoke.sh` — creates a governance `ApprovalRequest` as one
  user, **grants** it as a different user (SoD), asserts `APPROVED`; repeats and
  **denies**, asserts `DENIED`; drains the governance outbox and asserts both
  `approval.granted.v1` and `approval.denied.v1` were published, and that the relay
  consumed them.
- Wire all three (`ticket-lifecycle`, `approval-decision`, `conversation-multimodal`)
  into `.github/workflows/frontend-e2e.yml` after the existing smoke steps.

Both new scripts follow the established house style: `bash`, `set -euo pipefail`,
`say/ok/die` helpers, and — for the `support-console` Keycloak client, which is
standard-flow only — they flip `directAccessGrantsEnabled=true` via `kcadm` for the run
and **always** restore it on exit (`trap ... EXIT`), exactly as
`seed-demo-tickets.sh` / `seed-governance-policies.sh` already do.

## 3. `ticket-lifecycle-smoke.sh` — the chain

| step | call | actor | asserts |
|---|---|---|---|
| create | `POST /api/v1/tickets` | employee (`test.agent`) | `201`, captures `ticketId`/`version`/`displayId` |
| follow-up | `POST /api/v1/tickets/{id}/messages` (no `messageType`) | employee | `2xx` |
| triage | `POST /api/v1/tickets/{id}/triage` `If-Match` | support (`support.agent`) | `200`, `status == TRIAGED` |
| assign | `POST /api/v1/tickets/{id}/assign` | support | `200`, `status == ASSIGNED` |
| support reply | `POST .../messages` `messageType: PUBLIC_SUPPORT_MESSAGE` | support | `2xx` |
| start work | `POST .../status-transitions` `{targetStatus: IN_PROGRESS}` | support | `200`, `status == IN_PROGRESS` |
| resolve | `POST .../resolution` `{resolutionCode: FIXED, ...}` | support | `200`, `status == RESOLVED` |
| confirm | `POST .../resolution-confirmation` `{reasonCode: REQUESTER_CONFIRMED}` | employee | `200`, `status == CLOSED` |
| verify | `GET /api/v1/tickets/{id}` | employee | final `status == CLOSED`; timeline non-empty |
| outbox | `POST /internal/v1/outbox:dispatch` | support | `200` |
| learn | check | — | ticket-workflow published a `ticket.resolved` outbox row; event-relay logged `action=relay_delivery ... target=memory-knowledge ... status∈{200,4xx-ack}` |
| db | `psql` | — | `ticket.tickets` row for `{id}` has `status = CLOSED` |

Category `11111111-…` (NETWORK), queue `33333333-…`, agent `e85c3314-…` — the
V045/V046-seeded routing the other seed scripts already use.

## 4. `approval-decision-smoke.sh` — grant + deny

1. `POST /api/v1/approval-requests` as `support.agent` — `approvalType: TOOL_EXECUTION`,
   `riskLevel: MEDIUM` (MEDIUM avoids the `risk_clearance` ABAC gate that HIGH/CRITICAL
   add — SoD is what this smoke targets), a fresh `requestKey`/`requestHash`/
   `sourceRequestId`. `201`, capture `approvalRequestId`.
2. `POST /api/v1/approval-requests/{id}:grant` as `support.admin` (≠ requester → SoD
   satisfied) with `{sourceRequestId, requestHash, reason, commandIdempotencyKey}`.
   `200`, `status == APPROVED`.
3. Repeat 1 with a new request; `POST .../{id}:deny` as `support.admin`. `200`,
   `status == DENIED`.
4. `POST /api/v1/admin/outbox:dispatch` as `support.admin`. `200`.
5. Assert `governance.outbox_events` has a published `approval.granted.v1` **and** a
   published `approval.denied.v1` row (by `event_type`), and — best effort —
   `docker logs opsmind-event-relay` shows `action=relay_consumed ... approval.granted.v1`
   and `... approval.denied.v1`.

`GET /api/v1/approval-requests/{id}` is used for the status assertions so the smoke
never depends on the decision response body shape.

## 5. Files

New:

```
docs/specs/cross-cutting/SPEC-XIT-001-broader-integration-smokes.md   (this doc)
scripts/ticket-lifecycle-smoke.sh
scripts/approval-decision-smoke.sh
```

Modified:

```
.github/workflows/frontend-e2e.yml   (+ 3 steps: ticket-lifecycle, approval-decision, conversation-multimodal)
```

## 6. Non-goals

- Not a load test; one ticket, two approval requests.
- Escalation via a real agent conversation is left to `tool-execution-loop-smoke.sh` /
  `conversation-multimodal-smoke.sh` — this smoke enters at the employee's direct
  `POST /api/v1/tickets` (the deterministic, reasoning-independent portal entry point).
- Does not assert the *content* of a candidate memory — only that the source event was
  delivered. The candidate-pipeline internals have their own unit coverage
  (SPEC-MK-010+).
