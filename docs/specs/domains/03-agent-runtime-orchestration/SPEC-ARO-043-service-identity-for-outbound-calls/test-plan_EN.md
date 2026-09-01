# SPEC-ARO-043 — Test Plan

Goal: support `Service Identity for Outbound Calls`.

- Integration test against the real Keycloak realm (reusing the one stood up during the 2026-09-01 integration verification), asserting a genuine token is obtained and accepted by a real downstream endpoint.
- Failure test: Keycloak temporarily unavailable → the outbound call fails cleanly with a clear error, never a silent unauthenticated bypass.
- Refresh test: a near-expiry token is transparently refreshed without a visible failure surfacing to the caller.
- A static/log-scan check confirming no secret value appears in any log line or trace span.
