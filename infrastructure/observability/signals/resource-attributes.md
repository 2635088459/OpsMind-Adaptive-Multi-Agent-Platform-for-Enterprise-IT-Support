# Resource Attribute Convention

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-004
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: standard
> runbook: runbooks/ResourceAttributeViolation.md
> rollback: git revert <sha>; redeploy otel-collector
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-004-traceability.md

Every OpsMind telemetry **Resource** (the entity a signal is about — a service
instance) carries a standard attribute set so that metrics, logs, and traces from any
Java or Python producer join on the same keys and dashboards / alerts / correlation
work uniformly.

Machine-readable form: [`resource-attributes.yaml`](resource-attributes.yaml)
(schema [`../schemas/resource-attributes.schema.json`](../schemas/resource-attributes.schema.json)).
Golden payloads: [`fixtures/resource-attributes/`](fixtures/resource-attributes/).
This contract refines `allow_fields.resource` in
[`../governance/telemetry-governance.yaml`](../governance/telemetry-governance.yaml).

## 1. Levels

| Level | Meaning | Missing behavior |
|---|---|---|
| **required** | MUST be present on every signal in every environment | Collector stamps `opsmind.resource.violation` and substitutes a safe default; the signal is **not** dropped (ADR-0004). `ResourceAttributeViolation` alert fires. |
| **recommended** | SHOULD be present; needed for full triage / correlation | no enforcement; absence noted in dashboards |
| **optional** | MAY be present; environment- or platform-specific | — |

## 2. Required attributes

| Key | Value / format | Source | Notes |
|---|---|---|---|
| `service.name` | `^[a-z0-9]([a-z0-9-]{1,48}[a-z0-9])$` | `OTEL_SERVICE_NAME` / SDK | The deployable unit, e.g. `ticket-workflow-service`, `agent-runtime-service`. Matches the `services/<dir>` name. |
| `service.version` | `^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$` or `git:<12 hex>` | build metadata → `OTEL_RESOURCE_ATTRIBUTES` | SemVer or a pinned commit. Never `dev` / `latest`. |
| `service.namespace` | one of the domain slugs (see §5) | `OTEL_RESOURCE_ATTRIBUTES` | Groups services by logical domain (`ticket-workflow`, `agent-runtime`, `observability-platform`, …). Drives dashboard folders. |
| `deployment.environment` | `^(local|ci|staging|production)$` | `OTEL_RESOURCE_ATTRIBUTES`; Collector `resource` processor inserts it from `OTEL_DEPLOYMENT_ENVIRONMENT` if absent | Low cardinality. |
| `service.instance.id` | UUID or `<host>/<pid>` | SDK auto (`OTEL_RESOURCE_ATTRIBUTES` fallback) | Distinguishes replicas; used to de-dupe metrics. Not a Prometheus label (exemplars only). |
| `telemetry.sdk.language` | `^(java|python|nodejs|go|dotnet|rust|cpp|erlang|php|ruby|swift|webjs)$` | SDK auto | Triage: which SDK produced this. |

## 3. Recommended attributes

| Key | Source | Notes |
|---|---|---|
| `telemetry.sdk.name` / `telemetry.sdk.version` | SDK auto | usually `opentelemetry` + version |
| `telemetry.distro.name` / `telemetry.distro.version` | SDK auto (if a distro is used) | e.g. `opentelemetry-javaagent` |
| `host.name` | Collector `resourcedetection/system` | container / node hostname |
| `process.pid` / `process.runtime.name` / `process.runtime.version` | Collector `resourcedetection/process` + SDK | JVM / CPython details |
| `os.type` / `os.description` | Collector `resourcedetection/system` | |
| `service.instance.id` present **and stable** across a process lifetime | SDK | |

## 4. Optional attributes

| Key | When | Notes |
|---|---|---|
| `k8s.namespace.name`, `k8s.pod.name`, `k8s.deployment.name`, `k8s.node.name` | production on Kubernetes | added by `resourcedetection/k8s` or the Downward API in the production overlay (`SPEC-OP-008`) |
| `cloud.provider`, `cloud.region`, `cloud.availability_zone` | production in a cloud | `resourcedetection` cloud detectors |
| `container.id`, `container.image.name`, `container.image.tag` | containerized | |
| `opsmind.tenant.id` | multi-tenant deployments only | OpsMind is single-tenant today; when added it is **low-cardinality** (a tenant slug, never a customer name/email) and governed by `SPEC-OP-031`. Never a metric label. |

## 5. `service.namespace` allowed values

The eight logical domains plus shared:

```
user-access-authentication
ticket-workflow
agent-runtime
memory-knowledge
tool-integration
policy-approval-governance
evaluation-improvement
observability-platform
shared          # gateways, portal, cross-cutting libraries
```

## 6. Cardinality

- `service.name`, `service.namespace`, `deployment.environment`,
  `telemetry.sdk.language` — bounded, safe as metric labels.
- `service.instance.id`, `k8s.pod.name`, `container.id`, `process.pid`,
  `opsmind.tenant.id` — **unbounded or replica-scoped**: keep on the Resource for
  trace/log context and exemplars, never promote to a Prometheus label
  (`SPEC-OP-006`, `governance cardinality_budgets.*.forbidden_labels`).

## 7. Producer setup (reference)

Java (agent or SDK):

```
OTEL_SERVICE_NAME=ticket-workflow-service
OTEL_RESOURCE_ATTRIBUTES=service.namespace=ticket-workflow,service.version=1.4.2,deployment.environment=production
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
```

Python (opentelemetry-distro):

```
OTEL_SERVICE_NAME=memory-knowledge-service
OTEL_RESOURCE_ATTRIBUTES=service.namespace=memory-knowledge,service.version=git:9a1c4e77b0d2,deployment.environment=ci
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
```

`service.instance.id` and `telemetry.sdk.*` are set automatically by the SDK.

## 8. Enforcement

| Layer | Control |
|---|---|
| Producer | env vars above; conformance fixtures in `fixtures/resource-attributes/` are the golden reference for SPEC-OP-025+ cross-domain checks |
| Collector | `resource` processor inserts `deployment.environment` from `OTEL_DEPLOYMENT_ENVIRONMENT`; `resourcedetection` fills host/process/os/sdk; `transform/resource-contract` stamps `opsmind.resource.violation` + a safe `service.name` default when `service.name` is missing/blank (never drops) |
| CI | `scripts/validate-signal-contracts.py` — the `.yaml` conforms to its schema, every governance `required` key is a `required` attribute here, every conformant fixture passes and every non-conformant fixture is rejected, and the Collector config wires `resourcedetection` + `transform/resource-contract` |
| Runtime | `ResourceAttributeViolation` alert (`rules/alerting/signal-conformance.yml`) fires when any series carries `opsmind_resource_violation` |

## 9. Schema evolution

Additive (new optional/recommended attribute) → PR + `platform-observability`.
Changing a `value` pattern or promoting to `required` → treat as breaking per
`governance schema_review`: announce, allow a deprecation window, bump this file's
`version` minor/major.
