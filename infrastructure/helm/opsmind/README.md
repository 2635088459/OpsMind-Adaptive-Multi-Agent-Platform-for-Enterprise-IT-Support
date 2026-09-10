# OpsMind Helm chart

Deploys the eight application services, the `event-relay` sidecar
(SPEC-XREL-001 — `runtime: worker`, no Service, exec heartbeat probe), and the
two frontends from one values-driven template set
(`templates/deployment.yaml` / `service.yaml` loop over `.Values.services`).
It is the Kubernetes counterpart to
`infrastructure/docker-compose/full-platform.yml`.

## What this chart does NOT include

The stateful dependencies — **PostgreSQL, RabbitMQ, Keycloak, MinIO** — are
expected to already exist in the target namespace. Point `.Values.config.*` at
them. In a real cluster these are a managed database + a managed broker + a
Keycloak release; bundling them as subcharts here would tie the manifests to a
specific local topology. For a quick spin-up, install the community charts
first:

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install postgres bitnami/postgresql -n opsmind --create-namespace \
  --set auth.username=ticket_workflow --set auth.database=ticket_workflow
helm install rabbitmq bitnami/rabbitmq  -n opsmind
helm install keycloak bitnami/keycloak  -n opsmind --set auth.adminUser=admin
helm install minio    bitnami/minio     -n opsmind
```

(Use the pgvector image for Postgres — `memory.embeddings` needs the extension:
`--set image.repository=pgvector/pgvector --set image.tag=pg18`.)

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
