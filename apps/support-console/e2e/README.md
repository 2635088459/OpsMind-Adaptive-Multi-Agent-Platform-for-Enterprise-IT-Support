# support-console — Playwright E2E

Real browser, real backend. Same setup as `apps/employee-portal/e2e/` — read
that README for prerequisites (stack up, `127.0.0.1 keycloak` in `/etc/hosts`,
`playwright install chromium`). The app on :5174 can be `pnpm dev` or the
`opsmind-support-console` nginx container from `full-platform.yml`.

## Run

```
pnpm --filter support-console test:e2e
```

The `setup` project logs in once as `support.agent` (realm role
`support_agent`; the support-console Keycloak client is standard-flow only, so
this is a genuine browser Authorization-Code login) and saves the session to
`e2e/.auth/support.json` (git-ignored).

## Coverage

- `queue.spec.ts` — SPEC-SC-003/004/005/006: the queue loads real tickets from
  `GET /api/v1/support/tickets`, rows show a real priority chip + SLA state,
  and opening a row navigates to the ticket detail whose AI-activity panel
  runs its 3-way aggregation.
- `observability.spec.ts` — SPEC-SC-014/015: the `/observability` page is
  reachable from the top nav; entering a trace id hits the REAL authenticated
  BFF Tempo proxy (a 200/404, never a 401, proves the session cookie is
  forwarded) and entering a run id fires the 3 real chained reads against
  evaluation-improvement-service.
- `ticket-approval.spec.ts` — SPEC-SC-008/009: for a ticket whose real
  `GET /api/v1/governance-audit-records` returns a record with an
  `approvalRequestId`, the ticket detail page renders an `ApprovalCard`
  (fetched from the real `GET /api/v1/approval-requests/{id}`). Skips itself
  if no such ticket exists in the current environment.

## Env overrides

| var | default |
|---|---|
| `E2E_BASE_URL` | `http://localhost:5174` |
| `E2E_SUPPORT_USERNAME` | `support.agent` |
| `E2E_SUPPORT_PASSWORD` | `test-password` |
