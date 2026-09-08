#!/usr/bin/env bash
#
# Seed a few governance policies into 06-policy-approval-governance-service through
# the real admin pipeline: draft (SCOPE_policy:draft) -> review (SCOPE_policy:review)
# -> publish (SCOPE_policy:publish), respecting the reviewer/publisher
# separation-of-duties the service enforces (publisher != author, publisher != reviewer).
#
# The three policy scopes only ride on the `support-console` Keycloak client, which
# is standard-flow only. This script therefore flips directAccessGrantsEnabled=true
# on that client for the duration of the run (via kcadm) and ALWAYS flips it back on
# exit. Three distinct users cover the three SoD roles:
#   draft   -> test.agent
#   review  -> support.agent
#   publish -> support.admin
#
# Idempotent: a policyId that already has a version is skipped.
#
# Usage:  scripts/seed-governance-policies.sh
# Env:    PG_BASE_URL  (default http://localhost:8086)
#         KC_URL       (default http://localhost:8081)   -- host-mapped Keycloak
#         KC_CONTAINER (default opsmind-keycloak)

set -euo pipefail

PG_BASE_URL="${PG_BASE_URL:-http://localhost:8086}"
KC_URL="${KC_URL:-http://localhost:8081}"
KC_CONTAINER="${KC_CONTAINER:-opsmind-keycloak}"
REALM=opsmind
CLIENT_ID=support-console
CLIENT_SECRET="${SUPPORT_CONSOLE_SECRET:-support-console-secret}"

command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }

kc() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

echo "policy-approval-governance : $PG_BASE_URL"
echo "keycloak                   : $KC_URL (client $CLIENT_ID)"
echo

kc config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null
CLIENT_UUID="$(kc get clients -r "$REALM" -q clientId=$CLIENT_ID --fields id --format csv --noquotes 2>/dev/null | tr -d '\r')"
[ -n "$CLIENT_UUID" ] || { echo "could not find client $CLIENT_ID" >&2; exit 1; }

restore_client() {
  kc update "clients/$CLIENT_UUID" -r "$REALM" -s directAccessGrantsEnabled=false >/dev/null 2>&1 || true
  echo "restored directAccessGrantsEnabled=false on $CLIENT_ID"
}
trap restore_client EXIT

kc update "clients/$CLIENT_UUID" -r "$REALM" -s directAccessGrantsEnabled=true >/dev/null
echo "enabled directAccessGrantsEnabled on $CLIENT_ID (temporary)"

token_for() {
  curl -sS -X POST "$KC_URL/realms/$REALM/protocol/openid-connect/token" \
    -d grant_type=password -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" \
    -d "username=$1" -d "password=test-password" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("access_token") or ("ERR:"+json.dumps(d)))'
}

DRAFT_TOKEN="$(token_for test.agent)"
REVIEW_TOKEN="$(token_for support.agent)"
PUBLISH_TOKEN="$(token_for support.admin)"
for name_tok in "draft:$DRAFT_TOKEN" "review:$REVIEW_TOKEN" "publish:$PUBLISH_TOKEN"; do
  case "${name_tok#*:}" in ERR:*|"") echo "token acquisition failed for ${name_tok%%:*}: ${name_tok#*:}" >&2; exit 1;; esac
done
echo "acquired draft/review/publish tokens"
echo

PG_CODE="" ; PG_BODY=""
pg() { # $1=method $2=path $3=token ; $4=body(optional) ; sets PG_CODE + PG_BODY
  local tmp cid; tmp="$(mktemp)"
  cid="$(python3 -c 'import uuid;print(uuid.uuid4())')"
  if [ $# -ge 4 ]; then
    PG_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$1" "$PG_BASE_URL$2" -H "Authorization: Bearer $3" -H "X-Correlation-Id: $cid" -H 'Content-Type: application/json' -d "$4")"
  else
    PG_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$1" "$PG_BASE_URL$2" -H "Authorization: Bearer $3" -H "X-Correlation-Id: $cid")"
  fi
  PG_BODY="$(cat "$tmp")"; rm -f "$tmp"
}
jget() { printf '%s' "$1" | python3 -c "import sys,json;print(json.load(sys.stdin)$2)"; }

pg GET "/api/v1/policies" "$DRAFT_TOKEN"
EXISTING="$(printf '%s' "$PG_BODY" | python3 -c 'import sys,json
try: print(" ".join(p.get("policyId","") for p in json.load(sys.stdin)))
except Exception: print("")')"

# <policyId>|<policyName>|<scope>|<rules-json>
POLICIES="$(cat <<'EOF'
password-reset-self-service|Password reset self-service|action:password-reset|[{"ruleId":"pw-reset-allow","conditions":[],"effect":"ALLOW","riskLevel":"LOW","approvalRequired":false,"constraints":[]}]
account-provisioning|Account provisioning requires manager approval|action:account-provisioning|[{"ruleId":"provisioning-approval","conditions":[],"effect":"REQUIRE_APPROVAL","riskLevel":"MEDIUM","approvalRequired":true,"constraints":[]}]
bulk-data-export|Bulk data export|action:data-export|[{"ruleId":"large-export-approval","conditions":[{"attribute":"recordCount","operator":"GREATER_THAN_OR_EQUAL","value":"1000"}],"effect":"REQUIRE_APPROVAL","riskLevel":"HIGH","approvalRequired":true,"constraints":[{"type":"TIME_WINDOW","detail":"business-hours-only"}]},{"ruleId":"small-export-allow","conditions":[],"effect":"ALLOW_WITH_CONSTRAINTS","riskLevel":"LOW","approvalRequired":false,"constraints":[{"type":"READ_ONLY","detail":"result-set-audit-logged"}]}]
privileged-access-grant|Privileged access grant|action:privileged-access|[{"ruleId":"admin-role-critical","conditions":[{"attribute":"targetRole","operator":"EQUALS","value":"admin"}],"effect":"REQUIRE_APPROVAL","riskLevel":"CRITICAL","approvalRequired":true,"constraints":[{"type":"MAX_RETRY","detail":"1"}]}]
EOF
)"

published=0 skipped=0 failed=0
while IFS='|' read -r pid pname scope rules; do
  [ -n "$pid" ] || continue
  if printf ' %s ' "$EXISTING" | grep -q " $pid "; then
    echo "  SKIP    $pid  (already has a version)"; skipped=$((skipped+1)); continue
  fi

  draft_body="$(PID="$pid" PNAME="$pname" SCOPE="$scope" RULES="$rules" python3 -c '
import json, os
print(json.dumps({"policyId":os.environ["PID"],"policyName":os.environ["PNAME"],
  "scope":os.environ["SCOPE"],"rules":json.loads(os.environ["RULES"])}))')"

  pg POST "/api/v1/policies:draft" "$DRAFT_TOKEN" "$draft_body"
  if [ "$PG_CODE" != "201" ]; then echo "  FAIL    $pid  draft HTTP $PG_CODE  ${PG_BODY:0:180}"; failed=$((failed+1)); continue; fi
  pvid="$(jget "$PG_BODY" '["policyVersionId"]')"

  pg POST "/api/v1/policy-versions/${pvid}:review" "$REVIEW_TOKEN"
  if [ "$PG_CODE" != "200" ]; then echo "  FAIL    $pid  review HTTP $PG_CODE  ${PG_BODY:0:180}"; failed=$((failed+1)); continue; fi

  pg POST "/api/v1/policy-versions/${pvid}:publish" "$PUBLISH_TOKEN" '{}'
  if [ "$PG_CODE" != "200" ]; then echo "  FAIL    $pid  publish HTTP $PG_CODE  ${PG_BODY:0:180}"; failed=$((failed+1)); continue; fi
  echo "  OK      $pid  -> version $(jget "$PG_BODY" '["versionNumber"]') $(jget "$PG_BODY" '["status"]')"
  published=$((published+1))
done <<< "$POLICIES"

echo
echo "published=$published skipped=$skipped failed=$failed"
echo
echo "GET /api/v1/policies:"
pg GET "/api/v1/policies" "$DRAFT_TOKEN"
printf '%s' "$PG_BODY" | python3 -c '
import sys, json
for p in json.load(sys.stdin):
    print("  - %-28s %-22s %s" % (p.get("policyId"), p.get("scope",""), p.get("status", p.get("latestStatus",""))))
'

[ "$failed" -eq 0 ] || { echo "RESULT: FAIL"; exit 1; }
echo "RESULT: OK"
