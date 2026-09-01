# RuntimeMemoryBusinessSignals

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-026
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: audit
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-026-traceability.md

Covers both alerts SPEC-OP-026 adds: `AgentRuntimeTaskLeaseExpiredHigh`
(`agent` namespace, agent-runtime-service) and
`MemoryEmbeddingProviderFailing` (`knowledge` namespace, memory-knowledge-
service) — one file per this spec's own incident class, same grouping
convention as `IdentityTicketBusinessSignals.md`.

## Impact

- `AgentRuntimeTaskLeaseExpiredHigh`: Agent Tasks that should have completed
  are instead being recovered and re-claimed after their lease expires —
  workflows progress more slowly than expected, and any task whose worker
  keeps failing repeatedly will keep re-expiring instead of ever completing.
- `MemoryEmbeddingProviderFailing`: new Memory Candidates cannot be embedded,
  which blocks them from ever becoming searchable memory. Existing memory is
  unaffected — this is an ingestion-path impact, not a retrieval-path one.

## Detection

- Firing expressions:
  - `agent_runtime:task_lease_expired:rate5m > 0` for 10m
    (`sum(rate(agent_runtime_task_lease_expired_total[5m]))`)
  - `knowledge:embedding_failure:rate5m > 0` for 5m
    (`sum(rate(knowledge_embedding_failure_total[5m]))`)
- Dashboard: `dashboards/runtime-memory-business-signals.json` ("Runtime &
  Memory Business Signals")
- Correlation entry point: filter the dashboard's log panel by
  `service_namespace` (`agent-runtime` or `memory-knowledge`) and the
  relevant `trace_id` to see the exact workflow/task or candidate behind the
  alert.

## Triage

1. Check which alert fired — they have unrelated root causes.
2. For `AgentRuntimeTaskLeaseExpiredHigh`: compare
   `agent_runtime_task_lease_expired_total` against
   `agent_runtime_task_completed_total`/`agent_runtime_task_failed_total` over
   the same window. A rising expiry count with flat completed/failed counts
   means workers are silently dying (crash, OOM, deploy) rather than the task
   itself failing fast; check worker process health and recent deploys next.
3. For `MemoryEmbeddingProviderFailing`: check
   `memory_candidate_created_total` vs the embedding-failure rate to see what
   fraction of new candidates are affected, then inspect the embedding
   provider's own error responses (rate limit? auth? outage?) via
   memory-knowledge-service's own logs.

## Mitigation

- `AgentRuntimeTaskLeaseExpiredHigh`: agent-runtime-service's own recovery
  scan already reclaims expired-lease tasks automatically (no manual
  intervention needed for that part); if a specific worker pool is
  consistently the cause, that pool's own scaling/restart is domain 03's own
  operational call, not something this runbook instructs from the
  observability side.
- `MemoryEmbeddingProviderFailing`: no direct mitigation from this side — if
  the embedding provider itself is degraded (rate-limited, down), that is an
  external-dependency incident domain 04 owns; this runbook's job is
  detection and triage only ([forbidden-business-writes
  §4](../docs/forbidden-business-writes.md)).

## Resolution

- `AgentRuntimeTaskLeaseExpiredHigh`: durable fix is domain 03's — a fixed
  worker crash/hang cause, or a corrected lease-duration setting if leases are
  simply too short for the real task shape. Confirm resolution by watching
  `agent_runtime:task_lease_expired:rate5m` return to `0`.
- `MemoryEmbeddingProviderFailing`: durable fix is domain 04's — a restored
  embedding provider or a corrected client configuration. Confirm resolution
  by watching `knowledge:embedding_failure:rate5m` return to `0`.

## Rollback

Exact revert: `git revert <sha>` on this runbook / the two rule files
(`rules/recording/runtime-memory-business.yml`,
`rules/alerting/runtime-memory-business.yml`); `promtool check rules`;
recreate Prometheus. Reverting this spec only removes the ALERT — it does not
touch either producing domain's own metrics code.

## Escalation

- `AgentRuntimeTaskLeaseExpiredHigh` (`warning`): opens a ticket against
  agent-runtime-service's on-call (`service_namespace: agent-runtime`) —
  domain 08 defines and detects the signal, it does not remediate it
  (ADR-0004).
- `MemoryEmbeddingProviderFailing` (`critical`, paging): pages
  memory-knowledge-service's on-call (`service_namespace: memory-knowledge`)
  directly — a blocked ingestion pipeline is urgent enough to page, not queue
  as a ticket.

## Post-incident

Link the traceability entry
(`docs/traceability/domains/08-observability-platform/SPEC-OP-026-traceability.md`).
Residual risk: this spec only contracts and alerts on the subset of each
service's already-emitted metrics most directly tied to business-visible
failure (`agent_runtime_task_lease_expired_total`,
`knowledge_embedding_failure_total`) — the other 14 already-real
`agent_runtime_*` and 11 other `memory_*`/`knowledge_*` metrics are now
contracted (bounded labels enforced) but have no dedicated alert yet, and two
real histograms (`memory_search_latency_ms`,
`memory_graph_expansion_latency_ms`, plus `knowledge_document_ingestion_latency_ms`)
carry a pre-existing millisecond-unit non-conformance this spec documents but
does not fix — see the traceability doc's own note.
