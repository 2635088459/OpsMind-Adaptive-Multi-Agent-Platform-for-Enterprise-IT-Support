# Support Console — Security and Authorization

> **Document ID:** LLD-SC-011
> **Domain:** `10-support-console`
> **Status:** Draft

---

## 1. Authentication: the same mechanism as employee-portal, different roles/scopes

Also reuses `01-user-access-authentication`'s real Keycloak OIDC session mechanism, not reimplemented. The difference is the scopes granted to a support/admin account (all already-real backend scopes, not invented by this LLD):

```text
ticket:triage
ticket:assign
ticket:transition
Approval-related governance scope (06-policy-approval-governance's SecurityConfig currently only requires an authenticated caller — no fine-grained scope; this LLD honestly reflects this current state rather than assuming fine-grained permissions that don't actually exist yet)
```

## 2. A current state that must be honestly stated: the approval endpoints have no fine-grained authorization today

`06-policy-approval-governance`'s `ApprovalController` currently authorizes "any authenticated caller" (`.anyRequest().authenticated()`), with **no** fine-grained rule distinguishing, say, "which roles of agent may grant a high-risk request" (this is that domain's own actual LLD/code state, confirmed during the 2026-09-01 integration verification). This means:

- support-console **cannot** pretend there's a fine-grained permission boundary the backend doesn't actually enforce (e.g. "only admins can see the grant button" as a purely frontend hide — that is not a security boundary, only a UX nudge)
- If the business genuinely needs "only certain roles can grant a CRITICAL-risk request," that is a backend capability `06-policy-approval-governance` itself needs to add; this LLD only flags this current gap and does not fake an authorization boundary on the frontend

## 3. Information-disclosure risk of the AiLogEntry aggregated view

`AiLogEntry` aggregates across three domains, so an agent can see a broader surface of information than querying any single domain alone would show. It must be confirmed that the agent genuinely has real authorization on each of the three source domains (rather than the aggregated view incidentally surfacing data they have no right to see just because it's aggregated). Frontend responsibility: each of the three requests independently carries a real JWT; when any one returns 403 for lack of permission, the `PARTIAL` state must honestly show "no permission to view" rather than displaying empty data (to avoid confusing it with "temporarily unavailable" — the two mean completely different things to an agent).

## 4. XSS and content rendering

Same principle as domain 09 — text content from the backend aggregation (`AiLogEntry.step`, etc.) is always rendered as plain text/restricted Markdown, never injecting raw HTML.

## 5. Boundary with LangSmith/OpenTelemetry

Same as domain 09 (see its own `11-security-and-authorization` §5's corresponding content): support-console never holds a LangSmith key; both sections of the Observability page are only "external links to an already-existing system" — the frontend never calls either observability backend's write-side APIs directly.
