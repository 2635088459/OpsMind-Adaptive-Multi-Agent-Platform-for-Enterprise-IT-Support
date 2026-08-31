# Artifact Metadata Convention

> Spec: `SPEC-OP-001`
> Owner: `platform-observability`
> Status: authoritative — checked by `scripts/validate-observability-layout.py`
> Schema: [`../schemas/artifact-metadata.schema.json`](../schemas/artifact-metadata.schema.json)

Every governed artifact declares who owns it, what version it is, who may read it, how
long its output lives, how to operate it, and how to undo it. No exceptions.

## 1. Governed artifacts

Dashboards (`dashboards/*.json`), rule files (`rules/**/*.yml`), runbooks
(`runbooks/*.md`), and signal contracts (`signals/*.md`).

## 2. Required fields

| Field | Meaning | Example |
|---|---|---|
| `owner` | Accountable team handle | `platform-observability` |
| `version` | SemVer of this artifact | `1.0.0` |
| `spec` | Owning spec id | `SPEC-OP-016` |
| `access_policy` | Who may view / query | `viewer: all-engineering; edit: platform-observability` |
| `retention` | A retention **class name** from `governance/telemetry-governance.yaml` (`debug` / `standard` / `slo` / `audit`), optionally with a clarifying clause after `;` | `standard` · `audit; alert history` · `n/a (view only)` |
| `runbook` | Path or URL to operating instructions | `runbooks/HighRequestErrorRate.md` |
| `rollback` | Exact revert instruction | `git revert <sha>; redeploy prometheus overlay` |
| `audit_ref` | Where changes are recorded | `docs/traceability/domains/08-observability-platform/SPEC-OP-016-traceability.md` |

Optional: `slo_ref`, `dashboard`, `depends_on`, `deprecated_by`.

## 3. Encoding per file type

### Markdown (runbooks, signal contracts)

Blockquote header immediately after the H1:

```markdown
# High Request Error Rate

> owner: platform-observability
> version: 1.0.0
> spec: SPEC-OP-023
> access_policy: viewer: all-engineering; edit: platform-observability
> retention: alert history 90d
> runbook: self
> rollback: git revert <sha>
> audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-023-traceability.md
```

### YAML (rule files)

Top-of-file comment block, keys prefixed `# meta.`:

```yaml
# meta.owner: platform-observability
# meta.version: 1.0.0
# meta.spec: SPEC-OP-020
# meta.access_policy: viewer: all-engineering; edit: platform-observability
# meta.retention: recording-rule series 15d
# meta.runbook: runbooks/HighRequestErrorRate.md
# meta.rollback: git revert <sha>; promtool check rules; redeploy
# meta.audit_ref: docs/traceability/domains/08-observability-platform/SPEC-OP-020-traceability.md
groups: []
```

### JSON (Grafana dashboards)

Grafana ignores unknown top-level keys; carry metadata under `__opsmind_meta` and
mirror `owner` / `version` into the dashboard `tags`:

```json
{
  "__opsmind_meta": {
    "owner": "platform-observability",
    "version": "1.0.0",
    "spec": "SPEC-OP-016",
    "access_policy": "viewer: all-engineering; edit: platform-observability",
    "retention": "n/a (view only)",
    "runbook": "runbooks/GoldenPathServiceOverview.md",
    "rollback": "git revert <sha>; re-run grafana provisioning",
    "audit_ref": "docs/traceability/domains/08-observability-platform/SPEC-OP-016-traceability.md"
  },
  "tags": ["opsmind", "owner:platform-observability", "v:1.0.0"]
}
```

## 4. Lighter header for `base/` / `overlays/` config

Plain config files (Collector, Prometheus, Loki, Tempo, Alertmanager, Grafana
provisioning) are not "artifacts" in the sense above but still carry:

```yaml
# owner: platform-observability
# spec: SPEC-OP-008
# rollback: git revert <sha>; redeploy <component> <overlay>
```

## 5. Validation

`scripts/validate-observability-layout.py`:

- parses the header for each governed artifact,
- fails on any missing required field,
- fails on `version` that is not SemVer,
- fails on `runbook:` pointing to a non-existent path (except `self`),
- warns on `audit_ref` whose file does not yet exist (allowed while the owning spec is
  in progress).
