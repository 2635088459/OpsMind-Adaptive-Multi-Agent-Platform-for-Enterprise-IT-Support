# SPEC-OP-008 Traceability — OTLP Collector Gateway

> Domain: `08-observability-platform`
> Phase: `phase-02-collector-intake-processing`
> Status: implemented
> Verified: 2026-08-31 (validators + otelcol validate pass; full docker-compose smoke
> proves real TLS handshake, real bearer-token enforcement including a genuine 401,
> and real readiness — not just config parse; stack torn down)
> Owner: `platform-observability`

## 1. Objective mapping

Spec objective: *Deploy OTLP gRPC/HTTP gateway with TLS/auth, health 13133, metrics
8888, and readiness.*

| Spec area | Where |
|---|---|
| TLS | `collector/base/config.yaml` — `tls.cert_file`/`key_file` on both `grpc` and `http` `otlp` receiver protocols, fixed mount path `/etc/otelcol/tls/` |
| Auth | `extensions.bearertokenauth` (token `${env:OTEL_GATEWAY_AUTH_TOKEN}`), `auth.authenticator: bearertokenauth` on both protocols |
| Every environment, not just prod | ADR-0007 — a hard requirement in local/CI too; enforced by the smoke test's new 401 assertion |
| Cert never committed | `scripts/observability-stack.sh` `ensure_dev_tls()` generates into `collector/overlays/local/.tls/` (gitignored); `validate-observability-layout.py` now scans for a committed PEM key anywhere in the tree |
| Health 13133 / metrics 8888 | already existed (`SPEC-OP-002`); unchanged, left plaintext/unauthenticated as internal operational endpoints (not the producer-facing boundary this ADR is about) |
| Readiness | `health_check.check_collector_pipeline` (real pipeline-exporting check, not just process-alive) |

Deferred: a separate per-node agent tier (ADR-0001 left this optional, not required);
mTLS/client certs (ADR-0007 alternatives-considered); a managed secret store for the
token (production topology is still a documented target across every component, not
unique to this spec).

## 2. Files added / changed

```text
infrastructure/observability/
  docs/adr/0007-otlp-gateway-requires-tls-and-bearer-auth.md   NEW
  docs/adr/README.md                                          CHANGED (ADR-0007 row)
  collector/README.md                                         CHANGED (TLS/auth + values.env note)
  collector/base/config.yaml            CHANGED (bearertokenauth extension; tls+auth on
                                         both otlp protocols; check_collector_pipeline)
  collector/overlays/local/values.env   NEW (OTEL_GATEWAY_AUTH_TOKEN placeholder)

infrastructure/docker-compose/observability-stack.yml   CHANGED (token env var; .tls mount)
.gitignore                                              CHANGED (ignore the generated .tls/ dir)

scripts/observability-stack.sh                    CHANGED (ensure_dev_tls; https+auth header
                                                   on all pushes; 401 + readiness assertions)
scripts/validate-observability-layout.py          CHANGED (committed-private-key scan)
scripts/tests/test_validate_observability_layout.py  CHANGED (2 new tests)
.github/workflows/observability-platform-ci.yml   CHANGED (OTEL_GATEWAY_AUTH_TOKEN for
                                                   the otelcol-validate CI step)

docs/specs/domains/08-observability-platform/SPEC-OP-008-.../traceability-entry.yaml  CHANGED
docs/traceability/domains/08-observability-platform/SPEC-OP-008-traceability.md       NEW (this file)
```

## 3. Commands run and results (2026-08-31 UTC)

| Command | Result |
|---|---|
| `otelcol validate` with `OTEL_GATEWAY_AUTH_TOKEN` unset | **fails** — `extensions::bearertokenauth: no bearer token provided` (proves the auth requirement is real, not decorative) |
| `otelcol validate` with `OTEL_GATEWAY_AUTH_TOKEN` set | exit 0 |
| `uv run --with pyyaml python scripts/validate-observability-layout.py` | 0 errors, 0 warnings (private-key scan passes: the locally-generated `.tls/server.key` is correctly skipped as a dot-prefixed/gitignored path) |
| `uv run --with pyyaml python -m unittest discover -s scripts/tests` | **48 passed** (10 layout incl. 2 new + 9 governance + 29 signal-contracts) |
| `scripts/observability-stack.sh smoke` | **SMOKE: PASS.** `ensure_dev_tls` generated a real cert on first run; all 7 pre-existing OTLP pushes succeeded over `https://` with `Authorization: Bearer local-dev-change-me`; a **new** unauthenticated POST to `/v1/traces` returned exactly `401`; `GET http://localhost:13133/` reported ready. Every `SPEC-OP-002`~`007` assertion in the same run stayed green — Tempo/Loki/Prometheus content is unaffected by a transport-layer-only change. |
| `scripts/observability-stack.sh down` | stack + volumes removed, 0 containers |

## 4. Found + fixed mid-implementation

The CI workflow's `otelcol validate` step (`config` job) set
`OTEL_DEPLOYMENT_ENVIRONMENT` / `OTEL_TEMPO_ENDPOINT` / `OTEL_LOKI_ENDPOINT` but not
`OTEL_GATEWAY_AUTH_TOKEN` — adding the `bearertokenauth` extension without updating CI
would have broken that step outright on the next PR. Caught by re-running the exact
`docker run … validate` command CI uses, both before and after the fix.

## 5. Residual risks

| Risk | Severity | Mitigation / owner |
|---|---|---|
| Self-signed dev cert + `curl -k` (skip verification) is intentionally weaker than a real CA chain | Low | correct for local dev; production overlay (still a documented target, like every other component's `production/` overlay) requires a real cert |
| Every domain 01–07 producer SDK bootstrap must add `https://` + a bearer header — not yet done anywhere | Medium | tracked for `SPEC-OP-025`+ (cross-domain observability contracts); until then, only this repo's own smoke script proves the contract |
| Shared static bearer token (no per-producer identity/rotation) | Low | acceptable at this scale (ADR-0007 alternatives); revisit if a producer's threat model needs per-service tokens or mTLS |
| `health_check` / self-metrics ports stay unauthenticated | Low | deliberate — internal operational endpoints, not the producer-facing boundary; consistent with Prometheus's own unauthenticated `/metrics` in this local stack |

## 6. Sign-off

The OTLP gateway requires TLS and bearer-token authentication on both protocols in
every environment, proven by a real handshake and a real 401 in the smoke test, not
just a config parse. Readiness now reflects actual pipeline health. The dev
certificate is generated at runtime and never committed, and that invariant is now
CI-enforced, not just documented. `SPEC-OP-009` (Collector Processors And Routing)
continues phase-02.
