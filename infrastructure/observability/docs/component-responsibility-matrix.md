# Component Responsibility Matrix

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Status: authoritative
> Version pinning: [`../versions.env`](../versions.env)

Each row is a deployable. Ports are container-internal defaults; host exposure and
resource limits are finalized by the deployment spec (`SPEC-OP-002` local,
`SPEC-OP-008`+ per component). "Owns" = this component is the authority for that
concern. "Must not" = explicitly out of scope for this component.

## 1. OpenTelemetry Collector (gateway)

| Field | Value |
|---|---|
| Image | `otel/opentelemetry-collector-contrib` (pinned in `versions.env`) |
| Role | Sole OTLP ingestion boundary for application telemetry; normalize, redact, sample, batch, fan out |
| Ingress ports | `4317` OTLP gRPC, `4318` OTLP HTTP |
| Ops ports | `13133` health check, `8888` own metrics, `8889` Prometheus exporter, `1777` pprof, `55679` zpages |
| Egress | Prometheus (remote write or exposed for scrape), Loki (push), Tempo (OTLP) |
| Owns | Pipeline definition, resource-attribute enforcement, redaction processors, tail/head sampling policy, batch + retry + sending-queue backpressure, per-signal drop metrics |
| State | Stateless except a bounded on-disk sending queue (persistent volume, size-capped) |
| Failure behavior | Bounded queue → drop with counter increment; never blocks producers; `502`/refused on overload, producers fall back to their own bounded buffer |
| Config home | `collector/base/` + `collector/overlays/{local,ci,production}/` |
| Must not | Export to any business system; add business logic; retain telemetry beyond the queue |

## 2. Prometheus

| Field | Value |
|---|---|
| Image | `prom/prometheus` (pinned) |
| Role | Metrics storage, recording rules, alert rule evaluation |
| Ports | `9090` HTTP / PromQL / rules API |
| Owns | TSDB (WAL + blocks), scrape config for infrastructure exporters, recording rules, alert rules, `--storage.tsdb.retention.*` |
| State | Local TSDB on a persistent volume; capacity formula and retention in `SPEC-OP-015` |
| Failure behavior | Disk full → refuse writes, keep serving reads, fire self-alert; scrape target down → `up == 0` series, no crash |
| Config home | `prometheus/base/` + overlays; promoted rules in `rules/recording/`, `rules/alerting/` |
| Must not | Be treated as a business database; carry high-cardinality (user/ticket/workflow) labels; push data anywhere |

## 3. Loki

| Field | Value |
|---|---|
| Image | `grafana/loki` (pinned) |
| Role | Structured log storage and LogQL query |
| Ports | `3100` HTTP push / query |
| Owns | Log index + chunks, per-tenant stream limits, log retention and compaction |
| State | Index + chunk store on persistent volume or object storage (`SPEC-OP-015`) |
| Failure behavior | Ingestion limit exceeded → `429` to Collector (which buffers/drops with counter); read path stays up |
| Config home | `loki/base/` + overlays |
| Must not | Accept unredacted PII / secrets; be the system of record; index high-cardinality labels |

## 4. Tempo

| Field | Value |
|---|---|
| Image | `grafana/tempo` (pinned) |
| Role | Distributed trace storage and TraceQL query |
| Ports | `3200` HTTP query; internal OTLP `4317` (receives from Collector only, host port not exposed) |
| Owns | Trace block storage, trace retention, service-graph / span-metrics generation config |
| State | Trace blocks on persistent volume or object storage (`SPEC-OP-015`) |
| Failure behavior | Backpressure → Collector queue absorbs then drops with counter; read path unaffected |
| Config home | `tempo/base/` + overlays |
| Must not | Be a business database; retain traces beyond configured retention; accept direct producer traffic |

## 5. Grafana

| Field | Value |
|---|---|
| Image | `grafana/grafana` (pinned) |
| Role | Dashboards, Explore, correlation across metrics ↔ logs ↔ traces |
| Ports | `3000` HTTP UI / API |
| Owns | Dashboard provisioning, data-source provisioning (query-only), folder/permission model, org settings |
| State | Grafana DB (dashboards are provisioned from Git; DB holds only annotations, prefs) |
| Failure behavior | Grafana down → dashboards unavailable, alerting unaffected (Prometheus + Alertmanager own alerting); no business impact |
| Config home | `grafana/base/` (datasources, provisioning) + overlays; dashboard JSON in `dashboards/` |
| Must not | Hold write-capable data-source credentials; perform business writes; be the source of truth for dashboards (Git is) |

## 6. Alertmanager

| Field | Value |
|---|---|
| Image | `prom/alertmanager` (pinned) |
| Role | Deduplicate, group, route, throttle, and silence alert notifications |
| Ports | `9093` HTTP API / UI; `9094` cluster gossip (multi-replica only) |
| Owns | Routing tree, grouping, inhibition rules, receivers, silence store |
| State | Silence + notification-log store on persistent volume |
| Failure behavior | Alertmanager down → Prometheus queues alerts and retries; notifications delayed, not lost within retry window; self-alert via a secondary path |
| Config home | `alertmanager/base/` + overlays |
| Must not | Call a business write API from any receiver; auto-remediate a domain |

## 7. Cross-cutting ownership

| Concern | Authority | Spec |
|---|---|---|
| Resource-attribute convention | `signals/` | `SPEC-OP-004` |
| HTTP / AMQP trace propagation (W3C) | `signals/` | `SPEC-OP-005` |
| Metric naming + cardinality budget | `signals/` | `SPEC-OP-006` |
| Structured log + redaction contract | `signals/` | `SPEC-OP-007` |
| Retention / compaction / storage sizing | per backend | `SPEC-OP-015` |
| SLO / error-budget model | `rules/` + `dashboards/` | `SPEC-OP-022`, `SPEC-OP-023` |
| Runbook catalog | `runbooks/` | `SPEC-OP-024` |
| Access control for the platform | `grafana/`, backends | `SPEC-OP-030` |
| Config change approval + audit | this tree + control-plane API | `SPEC-OP-032` |
| Self-monitoring + degraded mode | `dashboards/`, `rules/` | `SPEC-OP-033`, `SPEC-OP-034` |
