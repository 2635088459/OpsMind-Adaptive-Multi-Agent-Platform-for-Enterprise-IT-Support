# OpsMind Helm chart

Deploys the eight application services, the `event-relay` sidecar
(SPEC-XREL-001 — `runtime: worker`, no Service, exec heartbeat probe), and the
two frontends from one values-driven template set
(`templates/deployment.yaml` / `service.yaml` loop over `.Values.services`).
It is the Kubernetes counterpart to
`infrastructure/docker-compose/full-platform.yml`.

## Stateful dependencies — PostgreSQL, RabbitMQ, Keycloak, MinIO

**Production:** leave `deps.enabled=false` (the default). The four servers must
already resolve in the namespace at the Service names `config.*` points at
(`postgres`, `rabbitmq`, `keycloak`, `minio`); in a real cluster those are a
managed database + a managed broker + a Keycloak release, not this chart's job.
Point `config.*` at them and supply the passwords via the secrets file below.

**Demo / kind:** set `deps.enabled=true` and the chart also runs a single-replica
copy of each — the same images as `docker-compose/local+full-platform.yml`, wired
to those exact Service names. One command brings up a working stack:

```bash
helm install opsmind infrastructure/helm/opsmind \
  -n opsmind --create-namespace \
  --set deps.enabled=true \
  --set-file deps.keycloak.realmJson=infrastructure/keycloak/opsmind-realm.json \
  -f infrastructure/helm/opsmind/values-secrets.yaml     # for the OAuth client secrets
```

- `--set-file deps.keycloak.realmJson=…` keeps one canonical realm file (no copy
  into the chart). Omit it and Keycloak starts with no realm — every service's
  token validation then 404s.
- The three infra passwords (`DB_PASSWORD`, `RABBITMQ_PASSWORD`,
  `ATTACHMENT_STORAGE_SECRET_KEY`) default to a demo value when `deps.enabled` and
  left blank, so `-f values-secrets.yaml` is only needed for the Keycloak/OAuth
  client secrets, which must match the realm.
- First boot is slow: Keycloak re-runs Quarkus augmentation (~1–3 min) on every
  start, RabbitMQ ~1 min. `startupProbe`s absorb that; the app pods restart-loop
  until the deps are ready, which is expected. `kubectl -n opsmind get pods -w`.
- `deps.persistence.enabled=true` swaps the `emptyDir`s for a PVC per stateful
  dep (default `2Gi`, `deps.persistence.storageClass` / `.size`).
- Not production-grade: single replica, no backups, no HA, `start-dev` Keycloak.

`.github/workflows/deploy-ci.yml` runs a real `helm install --set deps.enabled=true`
into kind on every chart change and asserts each dep is actually usable (pgvector
present, AMQP serving, realm imported, MinIO healthy).

## Secrets

`Secret/opsmind-shared-secrets` carries `DB_PASSWORD`, `RABBITMQ_PASSWORD`, the
Keycloak client secrets, `OPENAI_API_KEY`, etc. Two ways to supply them:

1. **Chart-rendered** (`externalSecrets.enabled: false`, the default) — pass a
   gitignored values file:

   ```bash
   cp values-secrets.example.yaml values-secrets.yaml   # then edit
   helm upgrade --install opsmind . -n opsmind --create-namespace -f values-secrets.yaml
   ```

2. **External** (`externalSecrets.enabled: true`) — this chart renders no
   Secret; you create `opsmind-shared-secrets` via
   [external-secrets](https://external-secrets.io/) or
   [sealed-secrets](https://sealed-secrets.netlify.app/). Nothing else changes —
   every Deployment still `envFrom` that name.

`values.yaml` in the repo has **empty** secret values on purpose. `.gitignore`
blocks `values-secrets.yaml`. CI runs gitleaks (`.github/workflows/secret-scan.yml`).

## Deploy

```bash
helm lint infrastructure/helm/opsmind
helm upgrade --install opsmind infrastructure/helm/opsmind \
  -n opsmind --create-namespace \
  --set image.registry=ghcr.io/your-org --set image.tag=$GIT_SHA \
  -f values-secrets.yaml
```

## Images

The chart expects `<image.registry>/<service>:<image.tag>` for each service
(e.g. `ghcr.io/opsmind/agent-runtime-service:<sha>`). Each Java/Python service
already has a `Dockerfile`; the two frontends now do too
(`apps/*/Dockerfile`, repo-root build context). `.github/workflows/deploy-ci.yml`
lints and template-validates this chart on every change.

## CI coverage

| job | trigger | what it does |
|---|---|---|
| `helm` | every chart PR | `helm lint` + `helm template` + `kubeconform -strict` |
| `kind-deps-e2e` | every chart PR | real `helm install --set deps.enabled=true` into kind; asserts the 4 bundled deps are usable (pgvector, AMQP, realm imported, MinIO health) |
| `build-images` + `full-stack-k8s-e2e` | **nightly + `workflow_dispatch`** | builds all 11 images, loads them into kind, `helm install` the whole platform with bundled deps, waits for every Deployment `Available`, then runs `scripts/k8s-fullstack-smoke.sh` — a real employee JWT → conversation → escalation → ticket read back from ticket-workflow. This is the "it actually runs in a cluster" gate; too heavy (~30 min) for PRs. |

To run the full-stack smoke by hand against any cluster that already has the
platform installed: `NS=<namespace> scripts/k8s-fullstack-smoke.sh`.

## Verified against a real cluster (2026-09-10)

`deploy-ci.yml` only lints + `helm template` + `kubeconform`. A one-off
`helm install` into a local **kind** cluster (`kind create cluster`, k8s v1.31)
surfaced two things that template validation cannot catch — both now fixed:

1. **`runAsNonRoot` vs a named image user.**
   `defaults.podSecurityContext.runAsNonRoot: true` made **every** pod fail
   admission with
   `container has runAsNonRoot and image has non-numeric user (opsmind), cannot
   verify user is non-root` → `CreateContainerConfigError`. All nine service
   Dockerfiles ended on `USER opsmind` (a name); the kubelet resolves the name
   only by reading the image at runtime, which admission will not do. Fix: the
   Dockerfiles now pin the account to a numeric id
   (`useradd --uid 65532 --gid 65532 … && USER 65532`, the distroless "nonroot"
   convention) and `values.yaml` asserts the same id under
   `defaults.podSecurityContext` (`runAsUser`/`runAsGroup`/`fsGroup: 65532`) so
   admission can verify non-root without the image. `helm template` never sees
   this because the `USER` line lives in the image, not the manifest.

2. **`event-relay` liveness must survive an unreachable broker.**
   The relay's exec probe checks the mtime of a heartbeat file that the pika
   pump loop touches every ~5 s. When RabbitMQ is unreachable the pump never
   runs — `_connect()` throws — so a relay that is *correctly* sitting in its
   reconnect loop would go stale and k8s would CrashLoop-kill it. Fix: the
   `run_forever` reconnect branch now also touches the heartbeat (and records
   `event_relay_broker_errors_total` + a poll tick) on every retry, so a relay
   waiting on the broker stays `Ready`. Verified: with no broker in the cluster
   the relay pod held `1/1 Running`, `Restart Count: 0` for 3+ minutes, logging
   `action=relay_broker_error … reconnecting_in=5.0s`. `EventRelayBrokerUnreachable`
   (warning) is the alert that fires for this state instead.

The stateful deps still have to pre-exist (see above); this run used only the
relay image loaded via `kind load docker-image` to exercise the two fixes.
