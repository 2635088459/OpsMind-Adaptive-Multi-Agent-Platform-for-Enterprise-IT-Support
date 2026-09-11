#!/usr/bin/env bash
# Deploy the whole OpsMind platform to an existing GKE cluster:
#   Artifact Registry repo -> Cloud Build all 11 images -> helm upgrade --install
#   (bundled deps) -> wait -> full-stack smoke.
#
# Prereqs (run once, in your own terminal — interactive):
#   gcloud auth login
#   gcloud auth application-default login
#
#   CLUSTER=my-cluster REGION=us-central1 scripts/deploy-gke.sh
#
# Env:
#   PROJECT     GCP project        (default: gcloud config core/project)
#   REGION      cluster + AR region (default: us-central1)
#   CLUSTER     GKE cluster name    (REQUIRED)
#   AR_REPO     Artifact Registry repo name (default: opsmind)
#   TAG         image tag           (default: v1)
#   NAMESPACE   k8s namespace       (default: opsmind)
#   SKIP_BUILD=1  reuse images already in AR at $TAG
set -euo pipefail

PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${REGION:-us-central1}"
CLUSTER="${CLUSTER:?set CLUSTER=<gke cluster name>}"
AR_REPO="${AR_REPO:-opsmind}"
TAG="${TAG:-v1}"
NAMESPACE="${NAMESPACE:-opsmind}"
AR="${REGION}-docker.pkg.dev/${PROJECT}/${AR_REPO}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="$HERE/infrastructure/helm/opsmind"
REALM="$HERE/infrastructure/keycloak/opsmind-realm.json"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

command -v gcloud >/dev/null || die "gcloud required"
command -v helm   >/dev/null || die "helm required"
command -v kubectl >/dev/null || die "kubectl required"
[ -n "$PROJECT" ] || die "no project — set PROJECT= or 'gcloud config set project ...'"
gcloud auth print-access-token >/dev/null 2>&1 || die "gcloud not authenticated — run 'gcloud auth login' first"
[ -f "$REALM" ] || die "realm file not found: $REALM"

echo "project=$PROJECT region=$REGION cluster=$CLUSTER"
echo "images -> $AR/<svc>:$TAG   namespace=$NAMESPACE"

say "1. enable APIs (idempotent)"
gcloud services enable artifactregistry.googleapis.com cloudbuild.googleapis.com container.googleapis.com --project "$PROJECT"
ok "APIs enabled"

say "2. Artifact Registry repo $AR_REPO ($REGION)"
if ! gcloud artifacts repositories describe "$AR_REPO" --location "$REGION" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud artifacts repositories create "$AR_REPO" --repository-format=docker \
    --location "$REGION" --project "$PROJECT" --description "OpsMind platform images"
  ok "created"
else
  ok "exists"
fi

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  say "3. Cloud Build — build + push all 11 images (parallel)"
  gcloud builds submit "$HERE" --project "$PROJECT" \
    --config "$HERE/infrastructure/gcp/cloudbuild.yaml" \
    --substitutions "_AR=${AR},_TAG=${TAG}"
  ok "images pushed to $AR"
else
  say "3. SKIP_BUILD=1 — reusing $AR/*:$TAG"
fi

say "4. cluster credentials"
gcloud container clusters get-credentials "$CLUSTER" --region "$REGION" --project "$PROJECT"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
ok "context -> $(kubectl config current-context)"

say "5. helm upgrade --install (bundled deps, realm, realm-baked client secrets)"
helm upgrade --install opsmind "$CHART" \
  -n "$NAMESPACE" \
  --set image.registry="$AR" --set image.tag="$TAG" --set image.pullPolicy=IfNotPresent \
  --set deps.enabled=true \
  --set-file deps.keycloak.realmJson="$REALM" \
  --set secrets.KEYCLOAK_CLIENT_SECRET=user-access-secret \
  --set secrets.SUPPORT_CONSOLE_CLIENT_SECRET=support-console-secret \
  --set secrets.AGENT_RUNTIME_SERVICE_CLIENT_SECRET=integration-test-secret \
  --wait --timeout 20m || true   # --wait may time out on the slow first boot; step 6 re-checks

say "6. wait for the bundled deps, then every application Deployment"
kubectl -n "$NAMESPACE" rollout status deploy/postgres --timeout=300s
kubectl -n "$NAMESPACE" rollout status deploy/minio    --timeout=300s
kubectl -n "$NAMESPACE" rollout status deploy/rabbitmq --timeout=360s
kubectl -n "$NAMESPACE" rollout status deploy/keycloak --timeout=600s
kubectl -n "$NAMESPACE" wait --for=condition=Available deployment --all --timeout=1200s

say "7. full-stack cross-service smoke"
NS="$NAMESPACE" bash "$HERE/scripts/k8s-fullstack-smoke.sh"

cat <<EOF

$(printf '\033[1;32mDEPLOYED\033[0m') — OpsMind is running in $CLUSTER / namespace $NAMESPACE.

  kubectl -n $NAMESPACE get pods
  kubectl -n $NAMESPACE port-forward svc/employee-portal 8080:80      # then http://localhost:8080

Tear down (stops the pod-hour billing):
  helm uninstall opsmind -n $NAMESPACE
  kubectl delete namespace $NAMESPACE
EOF
