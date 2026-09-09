#!/usr/bin/env bash
#
# Register a set of business tool connectors into 05-tool-integration-gateway so
# the support-console /admin/connectors surface (and the capability registry the
# agent runtime reads) has real domain connectors, not only the built-in
# keycloak/scope-audit ones.
#
# Connectors registered here bind to the gateway's default placeholder adapter
# (a real downstream adapter is per-connector wiring, not data) — they are real
# manifest rows / capability-registry entries, which is what the admin surface
# lists. They are NOT executed here: the dispatch worker is deliberately never
# run as a process in this codebase, so tool_executions/tool_results stay empty
# until that path is exercised.
#
# Idempotent: a connector whose name is already registered is skipped.
#
# Usage:  scripts/seed-tool-connectors.sh
# Env:    TG_BASE_URL (default http://localhost:8020)

set -euo pipefail

TG_BASE_URL="${TG_BASE_URL:-http://localhost:8020}"
BASE="$TG_BASE_URL/internal/tool-gateway/v1"
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }
uuid() { python3 -c 'import uuid;print(uuid.uuid4())'; }

echo "tool-integration-gateway : $TG_BASE_URL"
echo

EXISTING="$(curl -sS "$BASE/connectors" | python3 -c 'import sys,json
try: print(" ".join(c["name"] for c in json.load(sys.stdin)))
except Exception: print("")')"

# name | version | risk | requires_approval | is_mutating | host | capabilities(csv)
# NOTE: identity.user.sendPasswordResetLink / .unlock / .resetPassword are NOT seeded
# here — they are real built-in connectors (KeycloakAdminConnectorAdapter, bound on
# container boot) that make genuine Keycloak Admin API calls, not the EchoConnector
# every admin POST /connectors registration binds.
CONNECTORS="$(cat <<'EOF'
housing-portal-api|1.0.0|MEDIUM|false|true|housing-portal|housing.maintenance.createRequest,housing.application.status
email-admin-service|1.0.0|MEDIUM|true|true|mail-admin|email.mailbox.grantSharedAccess,email.distributionList.addMember
vpn-access-service|1.0.0|HIGH|true|true|vpn-controller|vpn.access.grant,vpn.mfa.resetEnrollment
EOF
)"

registered=0 skipped=0 failed=0
while IFS='|' read -r name version risk approval mutating host caps; do
  [ -n "$name" ] || continue
  if printf ' %s ' "$EXISTING" | grep -q " $name "; then
    echo "  SKIP  $name  (already registered)"; skipped=$((skipped+1)); continue
  fi

  body="$(NAME="$name" VER="$version" RISK="$risk" APPROVAL="$approval" MUT="$mutating" HOST="$host" CAPS="$caps" CID="$(uuid)" python3 -c '
import json, os
n = os.environ["NAME"]
print(json.dumps({
  "name": n, "version": os.environ["VER"],
  "capability_names": os.environ["CAPS"].split(","),
  "input_schema_ref": "schema://" + n + "/input",
  "output_schema_ref": "schema://" + n + "/output",
  "risk_level": os.environ["RISK"],
  "requires_approval": os.environ["APPROVAL"] == "true",
  "is_mutating": os.environ["MUT"] == "true",
  "allowed_hosts": [os.environ["HOST"]],
  "allowed_requester_types": ["AGENT", "SYSTEM"],
  "connect_timeout_seconds": 5, "invoke_timeout_seconds": 30,
  "max_attempts": 3, "backoff_seconds": 5,
  "correlation_id": os.environ["CID"],
}))')"

  resp="$(curl -sS -o /tmp/tg_body.$$ -w '%{http_code}' -X POST "$BASE/connectors" -H 'Content-Type: application/json' -d "$body")"
  out="$(cat /tmp/tg_body.$$)"; rm -f /tmp/tg_body.$$
  if [ "$resp" = "200" ] || [ "$resp" = "201" ]; then
    cid="$(printf '%s' "$out" | python3 -c 'import sys,json;print(json.load(sys.stdin)["connector_id"])')"
    echo "  OK    $name  ($risk, approval=$approval, caps=$caps)  -> $cid"
    registered=$((registered+1))
  else
    echo "  FAIL  $name  HTTP $resp  ${out:0:200}"
    failed=$((failed+1))
  fi
done <<< "$CONNECTORS"

echo
echo "registered=$registered skipped=$skipped failed=$failed"

# Deprecate one connector so the admin surface shows a non-ACTIVE lifecycle state
# too. Guarded: only acts while it is still ACTIVE, so re-runs are a no-op.
DEP_STATE="$(curl -sS "$BASE/connectors" | python3 -c '
import sys, json
for c in json.load(sys.stdin):
    if c["name"] == "vpn-access-service":
        print(c["health_status"]); break
else:
    print("MISSING")')"
if [ "$DEP_STATE" = "ACTIVE" ]; then
  DEP_ID="$(curl -sS "$BASE/connectors" | python3 -c '
import sys, json
print(next(c["connector_id"] for c in json.load(sys.stdin) if c["name"] == "vpn-access-service"))')"
  curl -sS -o /dev/null -X PATCH "$BASE/connectors/$DEP_ID/status" -H 'Content-Type: application/json' \
    -d "{\"action\":\"DEPRECATE\",\"requested_by\":\"seed-tool-connectors.sh\",\"correlation_id\":\"$(uuid)\"}"
  echo "  deprecated vpn-access-service (lifecycle-state variety)"
fi

echo
echo "GET /connectors:"
curl -sS "$BASE/connectors" | python3 -c '
import sys, json
for c in json.load(sys.stdin):
    print("  - %-26s %-8s approval=%-5s %s" % (c["name"], c["risk_level"], c["requires_approval"], ",".join(c["capabilities"])))
'
echo
echo "GET /capabilities:"
curl -sS "$BASE/capabilities" | python3 -c '
import sys, json
for c in json.load(sys.stdin):
    print("  - %-38s %-8s approval=%-5s connectors=%d" % (c["capability_name"], c["risk_level"], c["requires_approval"], c["connector_count"]))
'

[ "$failed" -eq 0 ] || { echo "RESULT: FAIL"; exit 1; }
echo "RESULT: OK"
