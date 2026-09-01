# SPEC-ARO-043 — Acceptance Criteria

Goal: support `Service Identity for Outbound Calls`.

- A real client_credentials token is obtained and successfully authorizes a real call to `02-ticket-workflow`'s create-ticket endpoint.
- Token expiry is handled by transparent refresh — never surfaced as a visible failure to the calling conversation turn.
- No secret ever appears in logs, traces, or version control.
- If Keycloak is temporarily unavailable, outbound calls fail with a clear, actionable error — never a silent unauthenticated bypass.
