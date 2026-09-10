#!/usr/bin/env bash
# OpsMind ticket-lifecycle smoke (SPEC-XIT-001).
#
# Drives ONE ticket through EVERY real lifecycle transition and ASSERTS the status
# after each step — the chain seed-demo-tickets.sh walks but never checks:
#
#   create (employee) -> triage -> assign -> IN_PROGRESS -> resolve (support)
#                     -> confirm-resolution (employee) -> CLOSED
#
# Then it drains the ticket outbox and asserts the event-relay (SPEC-XREL-001)
# delivered the resulting `ticket.resolved.v1` to memory-knowledge's candidate-memory
# pipeline — so this also gates "the knowledge base grows from real resolutions".
#
# Requires the full platform up (V045/V046 routing seeds applied — they are part of
# the migrations that run on boot):
#   docker compose -f infrastructure/docker-compose/local-platform.yml \
#                  -f infrastructure/docker-compose/full-platform.yml up -d
#   scripts/ticket-lifecycle-smoke.sh
set -euo pipefail

TW="${TW_BASE_URL:-http://localhost:18080}"
KC="${KC_URL:-http://localhost:8081}"
KC_CONTAINER="${KC_CONTAINER:-opsmind-keycloak}"
PG_CONTAINER="${PG_CONTAINER:-opsmind-postgres}"
RELAY_CONTAINER="${RELAY_CONTAINER:-opsmind-event-relay}"
REALM=opsmind
SUPPORT_CLIENT=support-console
SUPPORT_SECRET="${SUPPORT_CONSOLE_SECRET:-support-console-secret}"

CATEGORY_ID=11111111-1111-1111-1111-111111111111    # NETWORK (V045)
QUEUE_ID=33333333-3333-3333-3333-333333333333       # network-support-team (V045)
AGENT_ID=e85c3314-64e6-48bb-b494-26d75fee189d       # Support Agent in the queue (V046)

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }
uuid() { python3 -c 'import uuid;print(uuid.uuid4())'; }
jget() { printf '%s' "$1" | python3 -c "import sys,json;print(json.load(sys.stdin)$2)"; }

command -v python3 >/dev/null || die "python3 required"
kc() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

say "0. support-console direct-grant (temporary)"
kc config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null
SUPPORT_UUID="$(kc get clients -r "$REALM" -q clientId=$SUPPORT_CLIENT --fields id --format csv --noquotes | tr -d '\r')"
[ -n "$SUPPORT_UUID" ] || die "client $SUPPORT_CLIENT not found"
restore() { kc update "clients/$SUPPORT_UUID" -r "$REALM" -s directAccessGrantsEnabled=false >/dev/null 2>&1 || true; }
trap restore EXIT
kc update "clients/$SUPPORT_UUID" -r "$REALM" -s directAccessGrantsEnabled=true >/dev/null
ok "enabled (will restore on exit)"

say "1. tokens"
EMP_TOKEN="$(curl -sS -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=employee-test-client -d username=test.agent -d password=test-password \
  | jget "['access_token']")"
SUP_TOKEN="$(curl -sS -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=$SUPPORT_CLIENT -d "client_secret=$SUPPORT_SECRET" \
  -d username=support.agent -d password=test-password | jget "['access_token']")"
[ -n "$EMP_TOKEN" ] && [ "$EMP_TOKEN" != "None" ] || die "no employee token"
[ -n "$SUP_TOKEN" ] && [ "$SUP_TOKEN" != "None" ] || die "no support token"
ok "employee + support tokens"

RESP=""; CODE=""
call() { # $1=method $2=path $3=token $4=body("" none) $5=ifmatch("" none)
  local hdr=(-H "Authorization: Bearer $3" -H "X-Correlation-Id: $(uuid)" -H "Idempotency-Key: $(uuid)")
  [ -n "$4" ] && hdr+=(-H 'Content-Type: application/json' -d "$4")
  [ -n "$5" ] && hdr+=(-H "If-Match: \"$5\"")
  local tmp; tmp="$(mktemp)"
  CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$1" "$TW$2" "${hdr[@]}")"
  RESP="$(cat "$tmp")"; rm -f "$tmp"
}
status_of() { call GET "/api/v1/tickets/$1" "$EMP_TOKEN" "" ""; jget "$RESP" "['status']"; }
expect_status() { local got; got="$(status_of "$1")"; [ "$got" = "$2" ] || die "expected status $2, got $got"; ok "status = $2"; }

say "2. employee creates the ticket"
BODY='{"title":"[smoke] VPN drops every few minutes","description":"My VPN disconnects roughly every five minutes on office wifi. Wired is fine. Started after Monday maintenance.","applicationCode":"VPN","source":"PORTAL"}'
call POST "/api/v1/tickets" "$EMP_TOKEN" "$BODY" ""
[ "$CODE" = "201" ] || die "create HTTP $CODE ${RESP:0:200}"
TID="$(jget "$RESP" "['ticketId']")"; VER="$(jget "$RESP" "['version']")"; DISP="$(jget "$RESP" "['displayId']")"
ok "created $DISP ($TID) version=$VER"

say "3. employee follow-up message"
call POST "/api/v1/tickets/$TID/messages" "$EMP_TOKEN" '{"content":"Adding detail: it is intermittent, every few minutes, only on wifi."}' ""
[ "$CODE" = "200" ] || [ "$CODE" = "201" ] || die "message HTTP $CODE ${RESP:0:160}"
ok "message accepted ($CODE)"

say "4. support triages -> TRIAGED"
call POST "/api/v1/tickets/$TID/triage" "$SUP_TOKEN" \
  "{\"categoryId\":\"$CATEGORY_ID\",\"priority\":\"MEDIUM\",\"supportQueueId\":\"$QUEUE_ID\",\"reason\":\"Routing to network support.\"}" "$VER"
[ "$CODE" = "200" ] || die "triage HTTP $CODE ${RESP:0:200}"
VER="$(jget "$RESP" "['version']")"; expect_status "$TID" "TRIAGED"

say "5. support assigns -> ASSIGNED"
call POST "/api/v1/tickets/$TID/assign" "$SUP_TOKEN" \
  "{\"assigneeId\":\"$AGENT_ID\",\"reason\":\"Assigning to the on-shift network agent.\"}" "$VER"
[ "$CODE" = "200" ] || die "assign HTTP $CODE ${RESP:0:200}"
VER="$(jget "$RESP" "['version']")"; expect_status "$TID" "ASSIGNED"

say "6. support public reply"
call POST "/api/v1/tickets/$TID/messages" "$SUP_TOKEN" \
  '{"content":"Reproduced it, working the config now.","messageType":"PUBLIC_SUPPORT_MESSAGE"}' ""
ok "support message ($CODE)"

say "7. support starts work -> IN_PROGRESS"
call POST "/api/v1/tickets/$TID/status-transitions" "$SUP_TOKEN" \
  '{"targetStatus":"IN_PROGRESS","reason":"Started working the ticket."}' "$VER"
[ "$CODE" = "200" ] || die "status-transition HTTP $CODE ${RESP:0:200}"
VER="$(jget "$RESP" "['version']")"; expect_status "$TID" "IN_PROGRESS"

say "8. support resolves -> RESOLVED"
call POST "/api/v1/tickets/$TID/resolution" "$SUP_TOKEN" \
  '{"resolutionCode":"FIXED","resolutionSummary":"Root cause found in the wifi roaming config; corrected and verified with the requester."}' "$VER"
[ "$CODE" = "200" ] || die "resolution HTTP $CODE ${RESP:0:200}"
VER="$(jget "$RESP" "['version']")"; expect_status "$TID" "RESOLVED"

say "9. employee confirms -> CLOSED"
call POST "/api/v1/tickets/$TID/resolution-confirmation" "$EMP_TOKEN" \
  '{"reasonCode":"REQUESTER_CONFIRMED","reason":"Confirmed fixed, thanks."}' "$VER"
[ "$CODE" = "200" ] || die "resolution-confirmation HTTP $CODE ${RESP:0:200}"
expect_status "$TID" "CLOSED"

say "10. timeline is non-empty"
call GET "/api/v1/tickets/$TID/timeline" "$EMP_TOKEN" "" "" || true
ENTRIES="$(printf '%s' "$RESP" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); items=d.get("entries") or d.get("items") or d
    print(len(items) if isinstance(items,list) else 0)
except Exception: print(0)')"
[ "${ENTRIES:-0}" -ge 3 ] && ok "timeline has $ENTRIES entries" || echo "   (timeline endpoint returned $CODE / $ENTRIES entries — non-fatal)"

say "11. drain the ticket outbox -> RabbitMQ"
call POST "/internal/v1/outbox:dispatch" "$SUP_TOKEN" "" ""
[ "$CODE" = "200" ] || die "outbox dispatch HTTP $CODE ${RESP:0:160}"
ok "dispatched: ${RESP:0:100}"

say "12. DB: the ticket row is CLOSED and a ticket.resolved outbox row was published"
DB_STATUS="$(docker exec "$PG_CONTAINER" psql -U ticket_workflow -d ticket_workflow -tAc \
  "select status from ticket.tickets where id='$TID';" 2>/dev/null | tr -d '[:space:]' || echo '?')"
[ "$DB_STATUS" = "CLOSED" ] || die "ticket.tickets.status = '$DB_STATUS' (expected CLOSED)"
ok "ticket.tickets.status = CLOSED"
PUB="$(docker exec "$PG_CONTAINER" psql -U ticket_workflow -d ticket_workflow -tAc \
  "select count(*) from ticket.outbox_events where aggregate_id='$TID' and event_type like 'ticket.resolved%' and published_at is not null;" 2>/dev/null | tr -d '[:space:]' || echo 0)"
[ "${PUB:-0}" -ge 1 ] || die "no published ticket.resolved outbox row for $TID"
ok "ticket.resolved outbox row published"

say "13. event-relay delivered ticket.resolved.v1 to memory-knowledge"
# The dispatch-scheduler / relay run continuously; give the relay a moment then read its log.
sleep 8
LOG="$(docker logs --since 2m "$RELAY_CONTAINER" 2>&1 || true)"
if echo "$LOG" | grep -q "action=relay_delivery .*target=memory-knowledge .*path=/internal/memory/v1/events/ticket-resolved"; then
  echo "$LOG" | grep "target=memory-knowledge" | tail -3 | sed 's/^/   /'
  ok "relay -> memory-knowledge ticket-resolved delivery observed"
else
  echo "$LOG" | grep -E "relay_consumed|relay_settled|relay_delivery" | tail -8 | sed 's/^/   /' || true
  die "event-relay did not deliver ticket.resolved.v1 to memory-knowledge (is opsmind-event-relay up?)"
fi

printf '\n\033[1;32mPASS\033[0m — create -> triage -> assign -> IN_PROGRESS -> resolve -> confirm -> CLOSED, and the resolution fed memory-knowledge.\n'
