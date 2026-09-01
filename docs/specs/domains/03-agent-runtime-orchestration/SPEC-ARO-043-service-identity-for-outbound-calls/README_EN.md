# SPEC-ARO-043 — Service Identity for Outbound Calls

> Domain: Agent Runtime Orchestration
>
> Phase: 10 — Conversational Intake
>
> Service: `agent-runtime-service`
>
> LLD Mapping: `11-security`
>
> Document Status: Spec Planning

## 1. Goal

Give `agent-runtime-service` a real Keycloak client_credentials service identity, so its own outbound calls to `02-ticket-workflow`'s and `06-policy-approval-governance`'s real, authentication-enforced endpoints (needed by SPEC-ARO-038/040/041) carry a genuinely valid JWT. This is a prerequisite for those specs, not optional infrastructure.

## 2. Scope

Includes:

- A new, dedicated Keycloak client registration (structurally the same kind of client_credentials client built as `integration-test-client` during the 2026-09-01 integration verification, but a real, production-grade identity owned by this service);
- Token acquisition, in-memory caching, and transparent refresh inside `agent-runtime-service`;
- Granting only the scopes genuinely needed (`tickets:create`, `ticket:triage`, and whatever `06-policy-approval-governance` requires).

Excludes:

- Any change to `01-user-access-authentication`'s own Keycloak realm structure beyond adding this one client;
- Any human-facing authentication flow — this is a machine-to-machine identity only.

## 3. Core Rules

- The client secret is never committed to source — environment-injected only, following this project's already-established secret-handling convention.
- A token is acquired once and reused/refreshed across all outbound calls from this service — never re-authenticated per individual request.
- If a token cannot be obtained, the outbound call fails closed (a clear, visible error) — it never silently proceeds unauthenticated or with a stale/expired token.
