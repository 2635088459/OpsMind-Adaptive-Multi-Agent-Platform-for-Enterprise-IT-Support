# Per-spec traceability entries — 09-employee-portal

One `SPEC-XX-NNN-traceability-entry.yaml` per Feature Spec, mirroring the
per-spec file layout domains 02/03/08 use. Each file is the same spec's row
from `../traceability-matrix.yaml` (the format the domain's own roadmap §18
prescribes) expanded to its own file — the matrix stays the single source of
truth; these are generated from it. Schema per file:

```yaml
SPEC-XX-NNN:
  domain: 09-employee-portal
  phase: Phase-0N
  status: implemented[+backend-verified | +source-verified | +backend-reachable | +backend-pending | +session-auth]
  service: apps/<app>
  use_cases: [...]        # UC-XX-NN
  api: [...]              # real endpoints consumed
  invariants: [...]       # BI-XX-NNN
  lld_mapping: [...]      # LLD sections + upstream specs
  components: [...]       # real files under apps/<app>/src/ (+ backend files where a spec needed one)
  tests: [...]            # real *.test.ts(x) + e2e/*.spec.ts
  implementation_notes: >
    the specific live findings from the 2026-09-08 frontend integration verification
```
