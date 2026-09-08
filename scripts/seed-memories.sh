#!/usr/bin/env bash
#
# Seed a few curated long-term Memories into 04-memory-knowledge-service via the
# real admin pipeline (extract candidate -> validate -> approve/publish). Each
# memory's `content` carries explicit `<TYPE>: <name>` markers (SERVICE:, APPLICATION:,
# ...) so PublishMemoryService links its MEMORY_VERSION node to the shared entity
# nodes — which is what gives SearchMemoryService's graph-expansion path something
# VISIBLE to traverse. Some memories deliberately name the same service so the
# graph actually connects them.
#
# The marker tokens live in `content` (used for scoring) not `summary` (the
# snippet returned to the agent), so they never surface in a retrieval answer.
#
# Re-running is safe: each step is keyed by a stable idempotency_key.
#
# Usage:  scripts/seed-memories.sh
# Env:    MK_BASE_URL (default http://localhost:8010)

set -euo pipefail

MK_BASE_URL="${MK_BASE_URL:-http://localhost:8010}"
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }

echo "memory-knowledge-service : $MK_BASE_URL"
echo

# id | memory_type | summary | content (with markers)
MEMORIES=$(cat <<'EOF'
m-vpn-mfa-window|PROCEDURAL|VPN "authentication failed" errors spike right after the org-wide password rotation window; first confirm the user finished the new-password sync on every device before deeper troubleshooting.|Right after each org-wide password rotation, VPN authentication-failed reports jump. The resolution is almost always that the employee has not re-entered the new password on one device (phone mail, the VPN client itself, a second laptop). Confirm new-password sync everywhere before escalating. SERVICE: vpn
m-vpn-wifi-drop|PROCEDURAL|Repeated VPN disconnects are almost always an unstable local network, not the VPN service; moving to wired or another network resolves it.|A VPN session that connects then drops every few minutes is an unstable local link, not a VPN outage. Switching from wifi to wired, or to a different network such as a phone hotspot, resolves the large majority of these. SERVICE: vpn
m-housing-provision-delay|ORGANIZATIONAL|Housing Portal "account not provisioned" for a new hire clears itself within one hourly provisioning cycle; no ticket action unless it persists past two hours.|New hires frequently hit "account not provisioned" on the Housing Portal on day one. Provisioning runs hourly off the access grant, so it clears on its own. Only investigate if it is still failing more than two hours after the grant was approved. APPLICATION: housing-portal SERVICE: sso
m-printer-wifi|PROCEDURAL|The top cause of "printer broken" tickets from the annex is a laptop on wifi instead of Ethernet; jobs silently queue and never print.|Most "printer will not print" reports from the annex building are a laptop connected over wifi. Office printers are only reachable on the wired network, so the job queues and never prints. Wired Ethernet, or the VPN if only wifi is available, fixes it. SERVICE: printing
m-sso-cache-password|PROCEDURAL|After a school-account password change, lab-desktop login failures are nearly always the old password still cached in the OS keychain.|When an employee changes their school-account password and then cannot log into a shared lab desktop, the cause is nearly always the previous password cached by the operating system. Clearing the stored credential and signing in fresh resolves it. SERVICE: sso APPLICATION: school-account
EOF
)

API_CODE="" ; API_BODY=""
api() {
  # $1=path  $2=json body ; sets API_CODE + API_BODY in the current shell
  local path="$1" body="$2" tmp
  tmp="$(mktemp)"
  API_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "${MK_BASE_URL}${path}" \
    -H 'Content-Type: application/json' -d "$body")"
  API_BODY="$(cat "$tmp")"; rm -f "$tmp"
}

jget() { printf '%s' "$1" | python3 -c "import sys,json;print(json.load(sys.stdin)$2)"; }

published=0 failed=0
while IFS='|' read -r id mtype summary content; do
  [ -n "$id" ] || continue

  extract_body="$(MK_ID="$id" MK_TYPE="$mtype" MK_CONTENT="$content" python3 -c '
import json, os
print(json.dumps({
  "memory_type": os.environ["MK_TYPE"],
  "candidate_text": os.environ["MK_CONTENT"],
  "idempotency_key": "seed-"+os.environ["MK_ID"],
  "extracted_by": "seed-memories.sh",
  "source_refs": [{"source_type": "runbook", "source_id": "seed:"+os.environ["MK_ID"]}],
}))')"
  api /internal/memory/v1/admin/candidates "$extract_body"
  if [ "$API_CODE" != "201" ]; then echo "  FAIL   $id  extract HTTP $API_CODE  ${API_BODY:0:160}"; failed=$((failed+1)); continue; fi
  cand_id="$(jget "$API_BODY" '["candidate_id"]')"

  api "/internal/memory/v1/admin/candidates/${cand_id}/validate" '{"source_refs_trusted": true, "confidence_score": 0.85}'
  # A prior run already carried this candidate past validate (extract replays a
  # stale EXTRACTED snapshot, but the real row is terminal) -> nothing more to do.
  if [ "$API_CODE" = "409" ] && printf '%s' "$API_BODY" | grep -q INVALID_STATE_TRANSITION; then
    echo "  OK     $id  (already published)"; published=$((published+1)); continue
  fi
  if [ "$API_CODE" != "200" ]; then echo "  FAIL   $id  validate HTTP $API_CODE  ${API_BODY:0:160}"; failed=$((failed+1)); continue; fi

  approve_body="$(MK_ID="$id" MK_SUMMARY="$summary" MK_CONTENT="$content" python3 -c '
import json, os
print(json.dumps({
  "usefulness_score": 0.8,
  "published_by": "seed-memories.sh",
  "idempotency_key": "seed-pub-"+os.environ["MK_ID"],
  "content": os.environ["MK_CONTENT"],
  "summary": os.environ["MK_SUMMARY"],
  "source_trust_score": 0.9,
  "classification": "INTERNAL",
}))')"
  api "/internal/memory/v1/admin/candidates/${cand_id}/approve" "$approve_body"
  if [ "$API_CODE" != "200" ]; then echo "  FAIL   $id  approve HTTP $API_CODE  ${API_BODY:0:200}"; failed=$((failed+1)); continue; fi
  echo "  OK     $id  -> memory $(jget "$API_BODY" '["memory_id"]') v$(jget "$API_BODY" '["version"]')"
  published=$((published+1))
done <<< "$MEMORIES"

echo
echo "published=$published failed=$failed"

echo
echo "graph-expansion probe (include_graph_paths=true):"
for q in "vpn authentication failed after password change" "printer will not print in the annex" "new hire cannot sign in to housing portal"; do
  body="$(MK_Q="$q" python3 -c '
import json, os, uuid
print(json.dumps({
  "query": os.environ["MK_Q"], "requester_type": "EMPLOYEE", "requester_id": "seed-verify",
  "access_scope": {"tenant": "default", "role": "EMPLOYEE", "classification": "INTERNAL"},
  "correlation_id": str(uuid.uuid4()),
  "filters": {"max_results": 3, "include_graph_paths": True, "max_graph_depth": 2},
}))')"
  curl -sS -X POST "${MK_BASE_URL}/internal/memory/v1/search" -H 'Content-Type: application/json' -d "$body" \
  | python3 -c '
import sys, json
d = json.load(sys.stdin)
results = d["results"]
paths = [p for it in results for p in it.get("graph_paths", [])]
print("  q: " + sys.argv[1])
print("     results=%d graph_degraded=%s graph_paths=%d" % (len(results), d["graph_degraded"], len(paths)))
for p in paths[:4]:
    print("     - " + p["explanation"])
' "$q"
done

[ "$failed" -eq 0 ] || { echo "RESULT: FAIL"; exit 1; }
echo "RESULT: OK"
