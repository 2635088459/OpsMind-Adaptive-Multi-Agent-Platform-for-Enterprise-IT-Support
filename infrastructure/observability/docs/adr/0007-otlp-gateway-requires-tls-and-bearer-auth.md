# ADR-0007: The OTLP gateway requires TLS and bearer-token auth in every environment, including local

> Status: Accepted
> Date: 2026-08-31
> Spec: SPEC-OP-008
> Deciders: platform-observability

## Context

ADR-0001 makes the Collector the sole application ingestion boundary, but its
`otlp` receiver (gRPC `4317` / HTTP `4318`) has run **plaintext and unauthenticated**
since `SPEC-OP-002`. Any process that can reach the Collector's network can push
arbitrary telemetry (including impersonating a resource's `service.name`) or read
signals in flight. `SPEC-OP-008`'s objective — "Deploy OTLP gRPC/HTTP gateway with
TLS/auth, health 13133, metrics 8888, and readiness" — closes that gap.

## Decision

- Both `otlp` receiver protocols (`grpc`, `http`) require **TLS** (`tls.cert_file` /
  `tls.key_file`, fixed conventional mount path in `base/config.yaml`) and a
  **bearer-token authenticator** (`bearertokenauth` extension, token from
  `${env:OTEL_GATEWAY_AUTH_TOKEN}`) in **every** environment, local included. This is
  a deliberate departure from the pattern where local/CI run a weaker version of a
  production control — a producer service that only ever talks to a plaintext,
  unauthenticated local Collector would never notice a TLS/auth regression before
  production.
- The **certificate/key are never committed** (`repository-layout.md` §5: "private
  keys" are forbidden anywhere in this repo). Local/CI generate an ephemeral
  self-signed dev certificate at stack-`up` time
  (`scripts/observability-stack.sh`), written to a **gitignored** directory. The
  production overlay documents a real CA-issued certificate + a real secret-managed
  token; neither is checked in (still a documented target, matching every other
  `production/` overlay in this repository today).
- The `health_check` (`13133`) and self-metrics (`8888`) endpoints stay plaintext
  and unauthenticated — they are operational endpoints scraped by trusted
  in-cluster tooling (Docker healthcheck, Prometheus), not the producer-facing
  ingestion boundary this ADR is about.
- `health_check` gains `check_collector_pipeline`: readiness reflects whether the
  pipeline is actually exporting, not just that the process is alive.
- Single-gateway topology (no separate per-node agent tier) continues for this
  phase; ADR-0001 already left an agent tier as a future option, not a requirement.

## Consequences

- Every producer SDK bootstrap across domains 01–07 must set an OTLP exporter
  endpoint scheme of `https://`, a CA/skip-verify policy, and an
  `Authorization: Bearer <token>` header (or gRPC per-RPC credentials) — this is a
  new, previously-absent requirement on every producer, tracked for domain teams
  under `SPEC-OP-025`+.
- A missing/wrong token now hard-fails ingestion (401), which is intended: silently
  accepting unauthenticated telemetry would defeat the point.
- Local development requires one extra step (cert generation) before `up`/`smoke`;
  the wrapper script makes this transparent and idempotent.

## Alternatives considered

- **TLS/auth only in production, plaintext local/CI.** Rejected: exactly the
  "never tested until prod" risk this ADR exists to avoid.
- **mTLS (client certificates) instead of / in addition to bearer tokens.**
  Deferred: higher operational cost (per-producer cert issuance/rotation) for
  marginal benefit over a rotated shared bearer token at this scale; revisit if a
  producer's threat model needs it.
- **A managed secret store (Vault, KMS) for the token from day one.** Deferred:
  local/CI use a plain env var placeholder (matching this repo's existing
  `DB_PASSWORD=change-me` convention); production secret sourcing is out of scope
  until a production topology spec actually deploys it.
