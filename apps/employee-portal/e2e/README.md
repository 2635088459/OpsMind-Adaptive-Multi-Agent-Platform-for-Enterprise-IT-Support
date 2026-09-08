# employee-portal — Playwright E2E

Real browser, real backend. Unlike `pnpm test` (Vitest + MSW, every network
call mocked), these drive Chromium against the live local platform.

## Prerequisites

1. The full stack up:
   ```
   docker compose -f infrastructure/docker-compose/local-platform.yml \
                  -f infrastructure/docker-compose/full-platform.yml up -d
   ```
2. `/etc/hosts` contains `127.0.0.1 keycloak` — a real browser must resolve
   the same hostname the BFF's `iss` claim uses.
3. Chromium installed once: `pnpm exec playwright install chromium`.
4. Something serving the app on :5173 — either `pnpm dev`, or the
   `opsmind-employee-portal` nginx container from `full-platform.yml` (the
   Playwright config reuses whatever is already answering on the port).

## Run

```
pnpm --filter employee-portal test:e2e
```

The `setup` project logs in once (real Keycloak Authorization-Code + PKCE
through the BFF, as `test.agent`) and saves the session to
`e2e/.auth/employee.json` (git-ignored); every spec reuses it.

## Reasoning-mode-dependent specs

agent-runtime's decision (text / proposedAction / escalation) is only
deterministic under `CONVERSATION_REASONING_MODE=static`. Specs tagged
`@static-reasoning` `test.skip` themselves unless `E2E_REASONING_MODE=static`
is set AND the container is actually in that mode:

```
# in infrastructure/docker-compose/.env set CONVERSATION_REASONING_MODE=static
docker compose ... up -d --no-deps agent-runtime-service
E2E_REASONING_MODE=static pnpm --filter employee-portal test:e2e
```

## Known gap (see escalation.spec.ts)

`escalation.spec.ts` is `test.fixme` — `useResumeConversation` (SPEC-EP-015)
re-enables the composer for a *terminal* most-recent conversation, so once
`test.agent` has an escalated conversation the next send 409s and renders a
misleading "agent temporarily unavailable". Un-fixme when SPEC-EP-015 stops
resuming a terminal conversation as the active one (or seeds a non-IDLE turn
state for it).

## Env overrides

| var | default |
|---|---|
| `E2E_BASE_URL` | `http://localhost:5173` |
| `E2E_EMPLOYEE_USERNAME` | `test.agent` |
| `E2E_EMPLOYEE_PASSWORD` | `test-password` |
| `E2E_REASONING_MODE` | (unset) — set to `static` to enable `@static-reasoning` specs |
