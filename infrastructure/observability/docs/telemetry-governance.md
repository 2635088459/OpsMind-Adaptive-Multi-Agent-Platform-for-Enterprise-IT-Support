# Telemetry Governance Baseline

> Spec: `SPEC-OP-003`
> Owner: `platform-observability`
> Status: authoritative — enforced by `scripts/validate-telemetry-governance.py` + the Collector
> Machine-readable rulebook: [`../governance/telemetry-governance.yaml`](../governance/telemetry-governance.yaml)
> Schema: [`../schemas/telemetry-governance.schema.json`](../schemas/telemetry-governance.schema.json)

This is the rulebook every OpsMind signal must satisfy **before it reaches a backend**.
Phase-01 (`SPEC-OP-004`–`007`) builds producer-side contracts against it; the
Collector enforces the deny-list at the ingestion boundary.

## 1. Allow / deny fields

### Deny (`deny_fields`)

Attribute / label / log-field **keys** that must never be stored. Matched
case-insensitively against the full key and its last dotted segment. Covers auth
material, credentials, tokens, MFA/OTP, raw LLM prompt/completion, raw user text, and
a PII core (SSN, PAN, email address).

Enforcement is layered:

| Layer | Control |
|---|---|
| Producer | SDK config does not attach these (SPEC-OP-004/005/007). GenAI content capture is **off**. |
| Collector | `processors.transform/governance` in `collector/base/config.yaml` runs `delete_matching_keys(...)` over resource, span, log, and metric-datapoint attributes in every pipeline. The regex is derived from `deny_fields` and kept in sync by the validator. |
| CI | `scripts/validate-telemetry-governance.py` fails if the Collector regex does not cover every `deny_fields` pattern, and scans committed config / fixtures for deny-listed keys. |
| Backend | least-privilege store credentials; Grafana data sources query-only. |

Deny is **key-based**, not value-based. Value-level scrubbing of free-text log bodies
(e.g. an email inside a message) is `SPEC-OP-007`'s structured-log redaction contract.

### Allow (`allow_fields`)

Per signal type (`resource`, `span`, `log`, `metric_datapoint`): the `required` keys a
conformant producer must emit and the `recommended` keys dashboards / rules / correlation
rely on. Producers may add domain attributes; they may not omit `required` ones.
`SPEC-OP-004` turns `resource.required` into a hard conformance test.

## 2. Retention classes

Artifacts declare a **class name** in their `retention:` metadata field, not a raw
duration. Classes: `debug`, `standard`, `slo`, `audit` — each with `local` / `ci` /
`prod` durations in the rulebook. `prod` values are the intended target; `SPEC-OP-015`
sets the capacity-modelled production numbers and this file is updated in the same PR.

The `local` durations match the overlay `values.env` files shipped in `SPEC-OP-002`.

## 3. Signal owners

`signal_owners` maps a signal family (glob) to a **semantic owner** (the domain team
that defines what the signal means) and a **transport owner** (always
`platform-observability`). Schema-review requests are routed to the semantic owner.

## 4. Cardinality budgets

`cardinality_budgets` declares, per metric namespace: `max_label_keys` (non-resource
labels on one metric), `max_series` (soft ceiling for the namespace in one
environment), and `forbidden_labels` (keys that must never be a label — IDs, raw
paths, emails). A `global.max_series_total` ceiling guards Prometheus overall.

Enforcement: review against this table + `SPEC-OP-006` tooling (`promtool`,
`prometheus_tsdb_head_series` self-alert). `forbidden_labels` are also covered by the
Collector deny-list where they overlap (e.g. `session_id`, `email`).

## 5. Schema review

`additive-or-versioned`:

- **Additive** (new optional attribute, new metric, new log field): PR +
  `platform-observability` review + the semantic owner.
- **Breaking** (rename / remove / retype / meaning change): a **new version suffix**
  (`_v2`), a deprecation window, and a migration note in the PR. Never reuse a name
  with new meaning.
- Gate: the `governance` job in `.github/workflows/observability-platform-ci.yml`.

## 6. Exception workflow

A time-boxed, owned waiver to an allow / deny / budget rule. Use it only when a signal
genuinely needs a field the baseline forbids and the risk is accepted.

### Request

1. Open a ticket describing the signal, the exact rule, the field, why it is needed,
   and the mitigation (hashing, truncation, sampling, short retention).
2. Add an entry to `exceptions:` in `telemetry-governance.yaml`:

   ```yaml
   - id: TGX-001
     rule: deny_fields:token            # or allow_fields:metric_datapoint / cardinality_budgets:http
     scope: "metrics.agent.* datapoint attribute 'model_token' (hashed)"
     reason: "cost attribution per model needs a stable non-PII token bucket"
     owner: agent-runtime
     approved_by: "platform-observability; policy-approval-governance (high-risk: deny_field)"
     opened: 2026-09-01
     expires: 2026-11-30          # <= opened + 90d, MUST be in the future
     ticket: OPS-1234
   ```

3. PR reviewed by `platform-observability`. Any `deny_fields` waiver is **high-risk**
   and also requires domain-06 (policy-approval-governance) approval, recorded in
   `approved_by`.

### Rules the validator enforces

- `id` matches `TGX-\d{3,}`; all fields present.
- `opened` and `expires` are ISO dates; `expires` is **in the future** (expired →
  CI fails) and **≤ `opened` + 90 days** (longer → CI fails).
- A `deny_fields` waiver's `approved_by` must mention `policy-approval-governance`.

### Lifecycle

- **Renew**: new ticket, new PR, new `expires` (still ≤ +90d). Max two renewals before
  a design fix is required.
- **Close**: delete the entry when the signal is fixed; note it in the ticket.
- **Expiry with no action**: CI goes red on the next run touching this tree — the
  waiver is not silently extended.

## 7. Rollback

`git revert` the governance PR and redeploy the Collector (its
`transform/governance` processor is regenerated from `deny_fields` on the next
`SPEC-OP-006`+ automation, or hand-edited in lockstep today — the validator guarantees
they match).
