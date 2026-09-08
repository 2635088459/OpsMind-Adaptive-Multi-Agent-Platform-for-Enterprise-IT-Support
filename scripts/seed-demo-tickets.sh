#!/usr/bin/env bash
#
# Seed a spread of demo tickets into 02-ticket-workflow-service across the real
# lifecycle so the support-console queue / detail / timeline surfaces have variety
# (they otherwise show ~only NEW tickets). Drives the real endpoints:
#   create (employee) -> triage -> assign -> status IN_PROGRESS -> resolve (support)
#   -> confirm-resolution (employee)
# stopping different tickets at different states.
#
# Tokens:
#   employee  -> employee-test-client / test.agent  (direct grant already on;
#                scopes tickets:create + ticket:resolution-confirm)
#   support   -> support-console / support.agent  (real support user, so the JWT
#                carries a real sub; hard-coded actor_type=IT_SUPPORT +
#                support_teams=network-support-team + ticket:triage/assign/transition/
#                resolve scopes. This client is standard-flow only, so the script
#                flips directAccessGrantsEnabled on for the run and ALWAYS restores
#                it on exit.)
#
# Uses the NETWORK category / network-support-team queue / seeded agents that
# V045+V046 put in the local Postgres. Every mutating call carries If-Match
# (the ticket version is optimistic-locked) and X-Correlation-Id.
#
# Idempotency: if any ticket titled "[demo] ..." already exists, the script exits
# without creating more (set TICKET_SEED_ALLOW_DUP=1 to override).
#
# Usage:  scripts/seed-demo-tickets.sh
# Env:    TW_BASE_URL (default http://localhost:8082)  KC_URL (default http://localhost:8081)

set -euo pipefail

TW_BASE_URL="${TW_BASE_URL:-http://localhost:18080}"
KC_URL="${KC_URL:-http://localhost:8081}"
KC_CONTAINER="${KC_CONTAINER:-opsmind-keycloak}"
REALM=opsmind
SUPPORT_CLIENT=support-console
SUPPORT_SECRET="${SUPPORT_CONSOLE_SECRET:-support-console-secret}"
SUPPORT_USER=support.agent
EMPLOYEE_CLIENT=employee-test-client

CATEGORY_ID=11111111-1111-1111-1111-111111111111       # NETWORK  (V045)
QUEUE_ID=33333333-3333-3333-3333-333333333333          # network-support-team (V045)
AGENT_ID=e85c3314-64e6-48bb-b494-26d75fee189d          # Support Agent, in the queue (V046)

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }
kc() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
uuid() { python3 -c 'import uuid;print(uuid.uuid4())'; }
jget() { printf '%s' "$1" | python3 -c "import sys,json;print(json.load(sys.stdin)$2)"; }

echo "ticket-workflow : $TW_BASE_URL"
echo

kc config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null
SUPPORT_UUID="$(kc get clients -r "$REALM" -q clientId=$SUPPORT_CLIENT --fields id --format csv --noquotes | tr -d '\r')"
[ -n "$SUPPORT_UUID" ] || { echo "client $SUPPORT_CLIENT not found" >&2; exit 1; }
restore() { kc update "clients/$SUPPORT_UUID" -r "$REALM" -s directAccessGrantsEnabled=false >/dev/null 2>&1 || true; echo "restored directAccessGrantsEnabled=false on $SUPPORT_CLIENT"; }
trap restore EXIT
kc update "clients/$SUPPORT_UUID" -r "$REALM" -s directAccessGrantsEnabled=true >/dev/null
echo "enabled directAccessGrantsEnabled on $SUPPORT_CLIENT (temporary)"

tok() { # $1=client $2=secret($2 may be "") $3=username
  local data="grant_type=password&client_id=$1&username=$3&password=test-password"
  [ -n "${2:-}" ] && data="$data&client_secret=$2"
  curl -sS -X POST "$KC_URL/realms/$REALM/protocol/openid-connect/token" -d "$data" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("access_token") or "ERR:"+json.dumps(d))'
}
EMP_TOKEN="$(tok "$EMPLOYEE_CLIENT" "" test.agent)"
SUP_TOKEN="$(tok "$SUPPORT_CLIENT" "$SUPPORT_SECRET" "$SUPPORT_USER")"
case "$EMP_TOKEN$SUP_TOKEN" in *ERR:*) echo "token error: emp=$EMP_TOKEN sup=$SUP_TOKEN" >&2; exit 1;; esac
echo "acquired employee + support tokens"
echo

# --- idempotency guard ------------------------------------------------------
EXISTING="$(curl -sS "$TW_BASE_URL/api/v1/support/tickets?limit=100" -H "Authorization: Bearer $SUP_TOKEN" \
  | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); items=d.get("items") or d.get("tickets") or d
    print(sum(1 for t in items if str(t.get("title","")).startswith("[demo]")))
except Exception: print(0)')"
if [ "${EXISTING:-0}" -gt 0 ] && [ "${TICKET_SEED_ALLOW_DUP:-}" != "1" ]; then
  echo "already seeded: $EXISTING [demo] ticket(s) exist. Set TICKET_SEED_ALLOW_DUP=1 to add more."
  exit 0
fi

RESP=""; VER=""
call() { # $1=method $2=path $3=token $4=body("" for none) $5=ifmatch("" for none)
  local hdr=(-H "Authorization: Bearer $3" -H "X-Correlation-Id: $(uuid)" -H "Idempotency-Key: $(uuid)")
  [ -n "$4" ] && hdr+=(-H 'Content-Type: application/json' -d "$4")
  [ -n "$5" ] && hdr+=(-H "If-Match: \"$5\"")
  local tmp; tmp="$(mktemp)"
  CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$1" "$TW_BASE_URL$2" "${hdr[@]}")"
  RESP="$(cat "$tmp")"; rm -f "$tmp"
}

# ticketId|title|app|desc|stop_at   (stop_at one of: triaged assigned in_progress resolved closed)
TICKETS="$(cat <<'EOF'
d1|[demo] VPN drops every few minutes on office wifi|VPN|My VPN keeps disconnecting roughly every five minutes whenever I am on the office wifi. Wired works fine.|triaged
d2|[demo] Cannot reach the Housing Portal after login|HOUSING_PORTAL|I can sign in to SSO but the Housing Portal just shows a blank page. Cleared cache already.|assigned
d3|[demo] Shared mailbox missing from Outlook|EMAIL|I was granted access to the facilities shared mailbox yesterday but it still is not showing up in Outlook.|in_progress
d4|[demo] Grant elevated VPN access for a contractor|VPN|A contractor on my team needs elevated VPN access to reach the internal build servers for the next two weeks.|approval
d5|[demo] New laptop will not join the VPN|VPN|Brand new laptop, VPN client installed from the portal, but every connection attempt fails with an auth error.|resolved
d6|[demo] Email quota exceeded, cannot send|EMAIL|I hit my mailbox quota and can no longer send mail. Need help clearing space or an increase.|closed
EOF
)"

made=0
while IFS='|' read -r id title app desc stop; do
  [ -n "$id" ] || continue

  body="$(TITLE="$title" DESC="$desc" APP="$app" python3 -c '
import json,os
print(json.dumps({"title":os.environ["TITLE"],"description":os.environ["DESC"],
  "applicationCode":os.environ["APP"],"source":"PORTAL"}))')"
  call POST "/api/v1/tickets" "$EMP_TOKEN" "$body" ""
  [ "$CODE" = "201" ] || { echo "  FAIL $id create HTTP $CODE ${RESP:0:200}"; continue; }
  tid="$(jget "$RESP" '["ticketId"]')"; VER="$(jget "$RESP" '["version"]')"; disp="$(jget "$RESP" '["displayId"]')"
  printf '  %s  %-8s NEW' "$disp" "$id"

  # requester follow-up message (the employee endpoint infers messageType, so it
  # must NOT be sent — "messageType must not be provided by an Employee")
  call POST "/api/v1/tickets/$tid/messages" "$EMP_TOKEN" \
    '{"content":"Adding a bit more detail: this started after the maintenance window on Monday and it is intermittent."}' ""

  # triage
  tbody="$(python3 -c "import json;print(json.dumps({'categoryId':'$CATEGORY_ID','priority':'MEDIUM','supportQueueId':'$QUEUE_ID','reason':'Routing to the network support queue for triage.'}))")"
  call POST "/api/v1/tickets/$tid/triage" "$SUP_TOKEN" "$tbody" "$VER"
  [ "$CODE" = "200" ] || { echo "  -> FAIL triage HTTP $CODE ${RESP:0:200}"; continue; }
  VER="$(jget "$RESP" '["version"]')"; printf ' -> TRIAGED'
  [ "$stop" = "triaged" ] && { echo; made=$((made+1)); continue; }

  # assign
  abody="$(python3 -c "import json;print(json.dumps({'assigneeId':'$AGENT_ID','reason':'Assigning to the on-shift network support agent.'}))")"
  call POST "/api/v1/tickets/$tid/assign" "$SUP_TOKEN" "$abody" "$VER"
  [ "$CODE" = "200" ] || { echo "  -> FAIL assign HTTP $CODE ${RESP:0:200}"; continue; }
  VER="$(jget "$RESP" '["version"]')"; printf ' -> ASSIGNED'

  # support public reply on the thread
  call POST "/api/v1/tickets/$tid/messages" "$SUP_TOKEN" \
    '{"content":"Thanks for the detail. I have reproduced this and I am working through the network configuration now — will update you shortly.","messageType":"PUBLIC_SUPPORT_MESSAGE"}' ""

  [ "$stop" = "assigned" ] && { echo; made=$((made+1)); continue; }

  # to IN_PROGRESS
  sbody='{"targetStatus":"IN_PROGRESS","reason":"Support agent has started working the ticket."}'
  call POST "/api/v1/tickets/$tid/status-transitions" "$SUP_TOKEN" "$sbody" "$VER"
  [ "$CODE" = "200" ] || { echo "  -> FAIL transition HTTP $CODE ${RESP:0:200}"; continue; }
  VER="$(jget "$RESP" '["version"]')"; printf ' -> IN_PROGRESS'
  [ "$stop" = "in_progress" ] && { echo; made=$((made+1)); continue; }

  # request approval (IN_PROGRESS -> WAITING_FOR_APPROVAL) -> creates a real
  # governance approval request linked to this ticket
  if [ "$stop" = "approval" ]; then
    pbody='{"workflowId":"seed-demo","actionId":"grant-elevated-vpn","actionType":"vpn.access.grant","riskLevel":"HIGH","riskContext":{"target":"contractor","duration_days":14},"reason":"Contractor needs elevated VPN access to internal build servers for two weeks."}'
    call POST "/api/v1/tickets/$tid/approval-requests" "$SUP_TOKEN" "$pbody" "$VER"
    [ "$CODE" = "201" ] || { echo "  -> FAIL request-approval HTTP $CODE ${RESP:0:200}"; continue; }
    printf ' -> WAITING_FOR_APPROVAL'
    APPROVAL_TICKET_ID="$tid"
    echo; made=$((made+1)); continue
  fi

  # resolve
  rbody='{"resolutionCode":"FIXED","resolutionSummary":"Root cause identified and corrected; verified working with the requester before resolving."}'
  call POST "/api/v1/tickets/$tid/resolution" "$SUP_TOKEN" "$rbody" "$VER"
  [ "$CODE" = "200" ] || { echo "  -> FAIL resolve HTTP $CODE ${RESP:0:200}"; continue; }
  VER="$(jget "$RESP" '["version"]')"; printf ' -> RESOLVED'
  [ "$stop" = "resolved" ] && { echo; made=$((made+1)); continue; }

  # confirm (employee)
  cbody='{"reasonCode":"REQUESTER_CONFIRMED","reason":"Confirmed fixed, thanks."}'
  call POST "/api/v1/tickets/$tid/resolution-confirmation" "$EMP_TOKEN" "$cbody" "$VER"
  [ "$CODE" = "200" ] || { echo "  -> FAIL confirm HTTP $CODE ${RESP:0:200}"; continue; }
  printf ' -> CLOSED\n'; made=$((made+1))
done <<< "$TICKETS"

echo
echo "created $made demo ticket(s)"

# Drain the ticket outbox to RabbitMQ (no background dispatcher runs in this
# codebase) so the ticket.* events — including approval.requested for the
# WAITING_FOR_APPROVAL ticket — actually reach the other services.
call POST "/internal/v1/outbox:dispatch" "$SUP_TOKEN" "" ""
[ "$CODE" = "200" ] && echo "outbox dispatched: ${RESP:0:120}" || echo "outbox dispatch HTTP $CODE (non-fatal)"

# Emit the id of the ticket that has a linked approval request so CI can point
# support-console's ticket-approval / @sc-014 trace specs at a real row.
[ -n "${APPROVAL_TICKET_ID:-}" ] && echo "SEED_TICKET_WITH_APPROVAL=$APPROVAL_TICKET_ID"
echo
echo "support queue status spread:"
curl -sS "$TW_BASE_URL/api/v1/support/tickets?limit=100" -H "Authorization: Bearer $SUP_TOKEN" | python3 -c '
import sys,json,collections
d=json.load(sys.stdin); items=d.get("items") or d.get("tickets") or d
c=collections.Counter(t.get("status") for t in items)
for k,v in sorted(c.items()): print("  %-18s %d" % (k,v))
'
