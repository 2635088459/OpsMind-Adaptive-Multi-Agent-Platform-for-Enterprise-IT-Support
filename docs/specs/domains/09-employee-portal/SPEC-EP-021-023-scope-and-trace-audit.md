# SPEC-EP-021 / SPEC-EP-023 — Scope Hardening & Trace Propagation Coverage: Audit Report

> Both specs' own §15/§9 name this report (not a runtime feature) as the artifact they produce. Written after every prior domain 09 spec (SPEC-EP-001–020) was implemented for real, auditing the actual call sites rather than the specs' own aspirational text.

## Methodology
Every real network call site in `apps/employee-portal/src/` was enumerated via `grep -rn "fetch(" src/` (excluding tests), then each was traced to its real backend endpoint's own authorization requirement (read directly from that endpoint's own controller/router source, not assumed from this domain's own spec prose — the same discipline this project applies everywhere).

## Call site inventory

| Call site | Real endpoint | Scope enforced by that endpoint (read from its own source) | Token used | Finding |
|---|---|---|---|---|
| `src/lib/authClient.ts` (`fetchBrowserSessionToken`) | `GET /api/v1/session/browser-token` (user-access-authentication-service) | Session-cookie authenticated only, no OAuth scope involved | N/A (cookie) | None |
| `src/features/conversation/api.ts` (all 6 functions) | `agent-runtime-service` `/api/v1/conversations/**` | None — `interfaces/conversation/router.py` requires only a decodable `sub` claim, no scope check anywhere in that router | The one real BFF-relayed access token, used unchanged everywhere | None (endpoint is scope-unchecked; no under/over-scope possible) |
| `src/features/ticket/api.ts` (`getTicket`, `confirmResolution`, `reopenTicket`) | `ticket-workflow-service` `PublicTicketQueryController` / `ConfirmResolutionController` / `RequesterReopenTicketController` | None — confirmed by reading all three controllers directly; only `SecurityConfiguration`'s blanket `.anyRequest().authenticated()` applies | Same token | None |
| `src/features/ticket/api.ts` (`createTicketManually`) | `ticket-workflow-service` `PublicTicketController.createTicket` | `@PreAuthorize("hasAuthority('SCOPE_tickets:create')")` | Same token | **Real dependency**: this is the one endpoint in the entire app that actually gates on a scope. Confirmed live (2026-09-02) that the real token's `scope` claim includes `tickets:create` after the `basic`/`tickets:create` Keycloak client-scope fixes this session made. |
| `src/features/attachment/api.ts` (`uploadAttachment`) | `POST /api/v1/attachments` | N/A — endpoint does not exist anywhere in this platform yet (SPEC-EP-010 §6) | Same token | Not auditable until the shared attachments capability is designed; MSW-mocked only, as that spec's own Definition of Done already states. |
| `src/features/session/useOnlineStatus.ts` (heartbeat) | `agent-runtime-service` `GET /health` | None (public liveness) | None sent | None |
| `src/features/ticket/useTicketStatusStream.ts` (EventSource) | `GET /api/v1/tickets/{id}/events` | N/A — endpoint does not exist yet (SPEC-EP-014 §6) | Passed as a `token` query param (EventSource cannot set headers) | Not auditable until that endpoint is designed; flagged in that hook's own code comment already. |

## SPEC-EP-021 conclusion
**Zero scope findings requiring a code change.** The single fixed BFF-relayed token (`openid profile email tickets:create`, per user-access-authentication-service's real Keycloak client config) is used unchanged for every real call. `tickets:create` is the only scope any real endpoint in this call graph actually enforces, and the token carries it (confirmed live). `profile`/`email` are not consumed by any employee-portal frontend code today, but are a genuine, pre-existing requirement of `BrowserLoginSuccessHandler`'s own `OidcUser#getPreferredUsername()`/`getEmail()` calls (SPEC-UA-005, a real backend consumer outside this domain) — not an over-scope finding once that real cross-cutting consumer is accounted for, rather than assumed away.

## SPEC-EP-023 conclusion
**One real finding, fixed during this audit**: `fetchBrowserSessionToken()` (`src/lib/authClient.ts`) was the one call site not attaching a `traceparent` header — it runs before any access token exists, so it never went through the shared `authedFetch` wrapper that attaches one everywhere else. Fixed by adding `newTraceparent()` directly to that call, and — a second, cross-service finding this surfaced — user-access-authentication-service's own CORS `allowedHeaders` list did not include `traceparent` at all, which would have made the browser silently block the very header this fix adds; fixed in `SecurityConfig.java` and verified live via a real CORS preflight request. `useOnlineStatus.ts`'s heartbeat also gained a `traceparent` per the spec's own literal "no network call... is untraceable," even though it is unauthenticated and low-stakes. `useTicketStatusStream.ts`'s `EventSource` connection (SPEC-EP-014/020) carries a best-effort `traceparent` query parameter — flagged, like its `token` parameter, as provisional pending that endpoint's own real backend spec, since `EventSource` cannot set headers.

**Zero untraced call sites remain** across `src/`, confirmed by the same call-site inventory above.
