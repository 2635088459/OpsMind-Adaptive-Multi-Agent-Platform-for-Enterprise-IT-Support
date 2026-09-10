#!/usr/bin/env bash
# OpsMind approval grant/deny smoke (SPEC-XIT-001).
#
# The approval decision chain has no end-to-end assertion today. This creates a real
# governance ApprovalRequest as one user, GRANTS it as a different user (separation
# of duties: decider != requester), asserts APPROVED; repeats and DENIES, asserts
# DENIED; then drains the governance outbox and asserts approval.granted.v1 +
# approval.denied.v1 were published and the event-relay consumed them.
#
# Requires the full platform up:
#   docker compose -f infrastructure/docker-compose/local-platform.yml \
#                  -f infrastructure/docker-compose/full-platform.yml up -d
#   scripts/approval-decision-smoke.sh
set -euo pipefail

PG="${PG_BASE_URL:-http://localhost:8086}"
KC="${KC_URL:-http://localhost:8081}"
KC_CONTAINER="${KC_CONTAINER:-opsmind-keycloak}"
DB_CONTAINER="${PG_CONTAINER:-opsmind-postgres}"
RELAY_CONTAINER="${RELAY_CONTAINER:-opsmind-event-relay}"
REALM=opsmind
CLIENT_ID=support-console
CLIENT_SECRET="${SUPPORT_CONSOLE_SECRET:-support-console-secret}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }
uuid() { python3 -c 'import uuid;print(uuid.uuid4())'; }
jget() { printf '%s' "$1" | python3 -c "import sys,json;print(json.load(sys.stdin)$2)"; }
jpipe() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }  # reads stdin

command -v python3 >/dev/null || die "python3 required"
kc() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

say "0. support-console direct-grant (temporary)"
kc config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null
CLIENT_UUID="$(kc get clients -r "$REALM" -q clientId=$CLIENT_ID --fields id --format csv --noquotes | tr -d '\r')"
[ -n "$CLIENT_UUID" ] || die "client $CLIENT_ID not found"
restore() { kc update "clients/$CLIENT_UUID" -r "$REALM" -s directAccessGrantsEnabled=false >/dev/null 2>&1 || true; }
trap restore EXIT
kc update "clients/$CLIENT_UUID" -r "$REALM" -s directAccessGrantsEnabled=true >/dev/null
ok "enabled (will restore on exit)"

say "1. tokens: support.agent (requester) + support.admin (decider)"
token_for() {
  curl -sS -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
    -d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d "username=$1" -d password=test-password | jpipe "['access_token']"
}
REQ_TOKEN="$(token_for support.agent)"
DEC_TOKEN="$(token_for support.admin)"
[ -n "$REQ_TOKEN" ] && [ "$REQ_TOKEN" != "None" ] || die "no support.agent token"
[ -n "$DEC_TOKEN" ] && [ "$DEC_TOKEN" != "None" ] || die "no support.admin token"
ok "requester + decider tokens (distinct users -> SoD satisfied)"

BODY=""; CODE=""
pg() { # $1=method $2=path $3=token $4=body("" none)
  local hdr=(-H "Authorization: Bearer $3" -H "X-Correlation-Id: $(uuid)")
  [ -n "$4" ] && hdr+=(-H 'Content-Type: application/json' -d "$4")
  local tmp; tmp="$(mktemp)"
  CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$1" "$PG$2" "${hdr[@]}")"
  BODY="$(cat "$tmp")"; rm -f "$tmp"
}
status_of() { pg GET "/api/v1/approval-requests/$1" "$REQ_TOKEN" ""; printf '%s' "$BODY" | jpipe "['status']"; }

# Create an ApprovalRequest. $1 = label. Sets globals LAST_ARID / LAST_SRC / LAST_HASH
# (NOT via $(...) — a subshell would drop the globals the decide step needs).
LAST_ARID=""; LAST_SRC=""; LAST_HASH=""
create_request() {
  local key hash src
  key="smoke-$(date +%s)-$RANDOM"; hash="hash-$(uuid)"; src="$(uuid)"
  local body
  body="$(KEY="$key" HASH="$hash" SRC="$src" python3 -c '
import json, os
print(json.dumps({
  "requestKey": os.environ["KEY"], "requestHash": os.environ["HASH"],
  "sourceDomain": "tool-integration-gateway", "sourceRequestId": os.environ["SRC"],
  "approvalType": "TOOL_EXECUTION", "riskLevel": "MEDIUM", "constraints": []
}))')"
  pg POST "/api/v1/approval-requests" "$REQ_TOKEN" "$body"
  [ "$CODE" = "201" ] || die "create approval request ($1) HTTP $CODE ${BODY:0:220}"
  LAST_SRC="$src"; LAST_HASH="$hash"
  LAST_ARID="$(printf '%s' "$BODY" | jpipe "['approvalRequestId']")"
}

decide() { # $1=arid $2=verb(grant|deny) $3=reason
  local body
  body="$(SRC="$LAST_SRC" HASH="$LAST_HASH" REASON="$3" KEY="cmd-$(uuid)" python3 -c '
import json, os
print(json.dumps({
  "sourceRequestId": os.environ["SRC"], "requestHash": os.environ["HASH"],
  "reason": os.environ["REASON"], "commandIdempotencyKey": os.environ["KEY"],
  "stepUpVerified": False
}))')"
  pg POST "/api/v1/approval-requests/$1:$2" "$DEC_TOKEN" "$body"
}

say "2. GRANT path"
create_request grant; ARID_G="$LAST_ARID"
ok "created $ARID_G (status $(status_of "$ARID_G"))"
decide "$ARID_G" grant "Approved for the smoke run."
[ "$CODE" = "200" ] || die "grant HTTP $CODE ${BODY:0:220}"
GS="$(status_of "$ARID_G")"; [ "$GS" = "APPROVED" ] || die "expected APPROVED, got $GS"
ok "granted -> APPROVED"

say "3. DENY path"
create_request deny; ARID_D="$LAST_ARID"
ok "created $ARID_D (status $(status_of "$ARID_D"))"
decide "$ARID_D" deny "Denied for the smoke run."
[ "$CODE" = "200" ] || die "deny HTTP $CODE ${BODY:0:220}"
DS="$(status_of "$ARID_D")"; [ "$DS" = "DENIED" ] || die "expected DENIED, got $DS"
ok "denied -> DENIED"

say "4. drain the governance outbox"
pg POST "/api/v1/admin/outbox:dispatch" "$DEC_TOKEN" ""
[ "$CODE" = "200" ] || die "outbox dispatch HTTP $CODE ${BODY:0:160}"
ok "dispatched: ${BODY:0:100}"

say "5. DB: approval.granted.v1 + approval.denied.v1 were published"
q() { docker exec "$DB_CONTAINER" psql -U ticket_workflow -d ticket_workflow -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
GRANTED="$(q "select count(*) from governance.outbox_events where event_type='approval.granted.v1' and published_at is not null;")"
DENIED="$(q "select count(*) from governance.outbox_events where event_type='approval.denied.v1' and published_at is not null;")"
echo "   published approval.granted.v1=$GRANTED  approval.denied.v1=$DENIED"
[ "${GRANTED:-0}" -ge 1 ] || die "no published approval.granted.v1 outbox row"
[ "${DENIED:-0}" -ge 1 ] || die "no published approval.denied.v1 outbox row"
ok "both decision events published"

say "6. event-relay received both decision events off opsmind.events"
# This smoke's ApprovalRequest carries no workflowInstanceId / toolRequestId (a pure
# governance decision), so the relay correctly plans zero deliveries and logs
# `relay_no_delivery` — the point here is that it *saw* both events on the bus. The
# resume-a-workflow fan-out is exercised by approval-loop-smoke.sh.
sleep 10
LOG="$(docker logs --since 3m "$RELAY_CONTAINER" 2>&1 || true)"
seen() { echo "$LOG" | grep -qE "action=relay_(consumed|no_delivery) .*event_type=$1"; }
echo "$LOG" | grep -E "action=relay_(consumed|no_delivery|settled) .*approval\.(granted|denied)" | tail -6 | sed 's/^/   /' || true
if seen "approval.granted.v1" && seen "approval.denied.v1"; then
  ok "relay received approval.granted.v1 + approval.denied.v1"
else
  die "event-relay did not receive both decision events off the bus (is opsmind-event-relay up?)"
fi

printf '\n\033[1;32mPASS\033[0m — ApprovalRequest granted (SoD) -> APPROVED, denied -> DENIED, both events published + seen by the relay.\n'
