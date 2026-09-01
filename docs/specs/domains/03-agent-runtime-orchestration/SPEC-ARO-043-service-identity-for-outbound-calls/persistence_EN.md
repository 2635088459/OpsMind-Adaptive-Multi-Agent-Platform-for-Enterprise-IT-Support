# SPEC-ARO-043 — Persistence Design

Goal: support `Service Identity for Outbound Calls`.

- No database table. The token is cached in memory only (short-lived, refreshed before expiry) — never persisted to Postgres or any durable store.
- The client secret lives only in environment configuration (matching this project's established secret-handling convention, e.g. `KEYCLOAK_CLIENT_SECRET`-style injection already used elsewhere in this platform) — never in a config file committed to source.
