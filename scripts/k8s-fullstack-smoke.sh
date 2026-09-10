#!/usr/bin/env bash
# OpsMind full-stack Kubernetes smoke.
#
# Proves the whole platform actually RUNS in a cluster, not just in docker-compose:
# a real employee JWT from the bundled Keycloak drives a conversation through
# agent-runtime, which (static reasoning -> deterministic) escalates and opens a
# real ticket in ticket-workflow, which we then read back — exercising Keycloak,
# agent-runtime, ticket-workflow, Postgres, and every migration having run on boot.
#
# Assumes: kubectl is pointed at the cluster, the release is installed in $NS with
# `deps.enabled=true`, and every Deployment is Available. Reaches services by
# port-forward so it works from a CI runner with no in-cluster shell.
#
#   NS=opsmind scripts/k8s-fullstack-smoke.sh
set -euo pipefail

NS="${NS:-opsmind}"
REALM="${REALM:-opsmind}"
EMP_USER="${EMP_USER:-test.agent}"
EMP_PASS="${EMP_PASS:-test-password}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }
j()   { python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)"; }

command -v kubectl >/dev/null || die "kubectl required"
command -v python3 >/dev/null || die "python3 required"

# --- port-forwards ------------------------------------------------------------
PF_PIDS=()
cleanup() { for p in "${PF_PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

pf() { # svc localport remoteport
  kubectl -n "$NS" port-forward "svc/$1" "$2:$3" >/dev/null 2>&1 &
  PF_PIDS+=("$!")
}
say "0. port-forward keycloak / agent-runtime / ticket-workflow"
pf keycloak 18080 8080
pf agent-runtime-service 18000 8000
pf ticket-workflow-service 18081 8080
KC=http://127.0.0.1:18080
AR=http://127.0.0.1:18000
TW=http://127.0.0.1:18081

# wait for the forwards to answer
for i in $(seq 1 30); do
  curl -sf "$KC/realms/$REALM/.well-known/openid-configuration" >/dev/null 2>&1 && break
  [ "$i" = 30 ] && die "keycloak port-forward never became reachable"
  sleep 2
done
ok "forwards up"

# --- 1. real employee token -------------------------------------------------
say "1. employee JWT from the bundled Keycloak ($EMP_USER)"
TOKEN="$(curl -sf -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=employee-test-client \
  -d "username=$EMP_USER" -d "password=$EMP_PASS" | j "['access_token']")"
[ -n "$TOKEN" ] && [ "$TOKEN" != "None" ] || die "no access_token from Keycloak"
ok "acquired a real JWT"

# --- 2. start a conversation (agent-runtime) -------------------------------
say "2. POST /api/v1/conversations (agent-runtime)"
RID="k8s-smoke-$(date +%s)-$RANDOM"
CONV="$(curl -sf -X POST "$AR/api/v1/conversations" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $RID-start")"
CID="$(printf '%s' "$CONV" | j "['conversation_id']")"
[ -n "$CID" ] && [ "$CID" != "None" ] || die "no conversation_id ($CONV)"
ok "conversation $CID"

# --- 3. a message that escalates -> ticket in ticket-workflow ------------
say "3. send a hardware message -> agent escalates -> opens a real ticket"
TURN="$(curl -sf --max-time 60 -X POST "$AR/api/v1/conversations/$CID/messages" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $RID-msg" \
  -H "Content-Type: application/json" \
  -d '{"text":"my company laptop physically will not turn on at all, no lights, nothing when I hold the power button"}')"
TYPE="$(printf '%s' "$TURN" | j "['type']")"
[ "$TYPE" = "escalation" ] || die "expected an escalation turn, got type=$TYPE ($TURN)"
TICKET_ID="$(printf '%s' "$TURN" | j "['ticket_id']")"
DISPLAY_ID="$(printf '%s' "$TURN" | j "['display_id']")"
[ -n "$TICKET_ID" ] && [ "$TICKET_ID" != "None" ] || die "escalation turn carried no ticket_id ($TURN)"
ok "escalated -> ticket $DISPLAY_ID ($TICKET_ID)"

# --- 4. read that ticket back from ticket-workflow ----------------------
say "4. GET /api/v1/tickets/{id} (ticket-workflow) with the same JWT"
TICKET="$(curl -sf "$TW/api/v1/tickets/$TICKET_ID" -H "Authorization: Bearer $TOKEN")"
TSTATUS="$(printf '%s' "$TICKET" | j "['status']")"
[ -n "$TSTATUS" ] && [ "$TSTATUS" != "None" ] || die "ticket-workflow did not return the ticket ($TICKET)"
ok "ticket-workflow has it — status=$TSTATUS"

# --- 5. soft: the escalation event reached event-relay -----------------
say "5. (soft) event-relay bridged the workflow/ticket event"
sleep 8
if kubectl -n "$NS" logs deploy/event-relay --since 3m 2>/dev/null | grep -q "action=relay_consumed"; then
  ok "event-relay consumed at least one event"
else
  printf '   \033[33mnote\033[0m no relay_consumed in the last 3m — the async fan-out may just be slow; not failing on it\n'
fi

printf '\n\033[1;32mPASS\033[0m — Keycloak -> agent-runtime -> ticket-workflow -> Postgres, live in the cluster.\n'
