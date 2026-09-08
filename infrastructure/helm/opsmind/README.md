# OpsMind Helm chart

Deploys the eight application services and the two frontends from one
values-driven template set (`templates/deployment.yaml` / `service.yaml` loop
over `.Values.services`). It is the Kubernetes counterpart to
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
