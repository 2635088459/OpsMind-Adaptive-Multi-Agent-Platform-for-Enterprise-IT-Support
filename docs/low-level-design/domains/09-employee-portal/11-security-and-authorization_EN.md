# Employee Portal — Security and Authorization

> **Document ID:** LLD-EP-011
> **Domain:** `09-employee-portal`
> **Status:** Draft

---

## 1. Authentication: fully reuses an already-built, already-verified mechanism

Login goes through `01-user-access-authentication`'s real Authorization Code + PKCE flow (Keycloak); this domain **implements no login logic of its own**, nor does it hold/refresh a JWT itself — the server-side session is cookie-based (`OPSMIND_SESSION`), already proven live during the 2026-09-01 integration verification (see `project-level-integration-verification` memory).

Browser-side API calls carry this session cookie (`SameSite=Lax`, `HttpOnly`, `Secure`); frontend JS never reads or manipulates this cookie's value directly.

## 2. New scopes required

`05-api-contracts` §2's new endpoints need new JWT/session scopes (the exact naming is defined by `03-agent-runtime-orchestration` in its own charter spec; this domain only lists the requirement):

```text
conversations:create
conversations:message
conversations:confirm-action
```

The existing `tickets:create` (a real scope already present in `02-ticket-workflow`) is reused for §1's direct-connect fallback creation path.

## 3. Attachment security

- Frontend pre-upload validation: file-type allowlist (images/PDF/common document formats), size cap — but **frontend validation is not the security boundary**; the real validation (including any future virus-scanning hook) must be enforced server-side by the shared attachments capability (echoing the ownership decision in `05-api-contracts` §3)
- An attachment's `objectRef` is an opaque reference, never exposed as a direct link to the underlying object store anywhere outside the rendering layer (e.g. never logged, never placed in a URL query string)

## 4. XSS protection

Text content returned by the agent (`Message.text`, `ProposedAction.summary`) is **always** rendered as plain text/restricted Markdown — raw HTML injection is never allowed, even though the content "seems trustworthy" (it comes from our own agent, after all) — its content may be indirectly influenced by knowledge-base/tool output, so it can never be assumed unconditionally safe.

## 5. Boundary with LangSmith/OpenTelemetry (echoing shared baseline §10)

The frontend **never** holds a LangSmith API key and never sends any data to LangSmith directly — the agent's observability data is produced and reported entirely server-side (`03-agent-runtime-orchestration`/`07-evaluation-improvement`). The frontend only participates in OpenTelemetry's distributed tracing (generating/propagating `trace_id`/`correlation_id` as a header on every API call) — this is pure engineering observability, covered in `12-observability-and-audit`.

## 6. Session fixation and cross-site request forgery

Reuses the protections `01-user-access-authentication` already built (PKCE itself prevents authorization-code interception attacks; `SameSite=Lax` cookies cover most CSRF scenarios). This domain does not reinvent this mechanism — it only needs to ensure every side-effecting request uses a non-GET method (already REST convention), never triggering a state change via a GET query string.
