#!/usr/bin/env bash
# OpsMind tool-execution loop smoke (P0 cross-service integration check).
#
# Proves the ONE thing per-service unit suites cannot: a confirmed self-service
# action actually crosses agent-runtime -> tool-integration-gateway, executes, and
# the WAITING_FOR_TOOL conversation gets woken. Before phase-05 (tool-gateway-
# mediation) agent-runtime's ToolGatewayPort was a log-only placeholder and this
# loop dead-ended.
#
# Requires the full platform up with:
#   TOOL_GATEWAY_MODE=http                (agent-runtime, full-platform.yml default)
#   scripts/seed-tool-connectors.sh already run (password-reset connector registered)
#
#   docker compose -f infrastructure/docker-compose/local-platform.yml \
#                  -f infrastructure/docker-compose/full-platform.yml up -d
#   scripts/tool-execution-loop-smoke.sh
set -euo pipefail

KC="${KEYCLOAK_URL:-http://localhost:8081}"
AR="${AGENT_RUNTIME_URL:-http://localhost:8000}"
TG="${TOOL_GATEWAY_URL:-http://localhost:8020}"
USER="${EMPLOYEE_USERNAME:-test.agent}"
PASS="${EMPLOYEE_PASSWORD:-test-password}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }
jqr() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)"; }

say "1. employee JWT"
TOKEN="$(curl -sS -X POST "$KC/realms/opsmind/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=employee-test-client -d "username=$USER" -d "password=$PASS" \
  | jqr "['access_token']")"
[ -n "$TOKEN" ] && [ "$TOKEN" != "None" ] || die "no token"
ok "got a token (${#TOKEN} chars)"
AUTH=(-H "Authorization: Bearer $TOKEN")

say "2. start a conversation"
CONV="$(curl -sS -X POST "$AR/api/v1/conversations" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"channel":"EMPLOYEE_PORTAL","message":"I need to reset my password"}' | jqr "['conversation_id']")"
[ -n "$CONV" ] && [ "$CONV" != "None" ] || die "no conversation_id"
ok "conversation_id=$CONV"

say "3. send a message that should propose the self-service action"
MSG="$(curl -sS -X POST "$AR/api/v1/conversations/$CONV/messages" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"text":"please reset my password, I am locked out"}')"
echo "$MSG" | python3 -m json.tool | sed 's/^/     /'
TYPE="$(echo "$MSG" | jqr "['type']")"
ACTION_ID="$(echo "$MSG" | jqr "['action_id']")"
[ "$TYPE" = "proposedAction" ] || die "expected type=proposedAction, got '$TYPE' (check CONVERSATION_REASONING_MODE)"
[ -n "$ACTION_ID" ] && [ "$ACTION_ID" != "None" ] || die "no action_id on the proposed action"
ok "proposedAction action_id=$ACTION_ID"

say "4. confirm the action -> creates the ToolRequest, workflow -> WAITING_FOR_TOOL"
CONFIRM="$(curl -sS -X POST "$AR/api/v1/conversations/$CONV/actions/$ACTION_ID/confirm" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{}')"
OUTCOME="$(echo "$CONFIRM" | jqr "['outcome']")"
echo "     outcome=$OUTCOME"
case "$OUTCOME" in done|still-processing) ok "confirm accepted (outcome=$OUTCOME)";; *) die "unexpected confirm outcome '$OUTCOME'";; esac

say "5. drive tool dispatch (agent-runtime -> tool-gateway create+execute)"
DISP="$(curl -sS -X POST "$AR/internal/agent-runtime/v1/admin/tool-requests/dispatch" -H "X-Actor-Id: smoke")"
echo "     $DISP"

say "6. the conversation must leave WAITING_FOR_TOOL (loop closed)"
STATE=""
for i in $(seq 1 20); do
  STATE="$(curl -sS "$AR/api/v1/conversations/$CONV" "${AUTH[@]}" | jqr "['state']")"
  [ "$STATE" != "WAITING_FOR_TOOL" ] && break
  curl -sS -o /dev/null -X POST "$AR/internal/agent-runtime/v1/admin/tool-requests/dispatch" -H "X-Actor-Id: smoke" || true
  sleep 2
done
echo "     final conversation state = $STATE"
[ "$STATE" != "WAITING_FOR_TOOL" ] || die "conversation still stuck in WAITING_FOR_TOOL — the loop did not close"
ok "workflow woke (state=$STATE)"

say "7. tool-integration-gateway completed a real request+execution for the capability"
DONE="$(docker exec opsmind-postgres psql -U ticket_workflow -d ticket_workflow -tAc \
  "select count(*) from tool.tool_requests where capability_name='identity.user.sendPasswordResetLink' and status='COMPLETED';" 2>/dev/null || echo 0)"
EXECS="$(docker exec opsmind-postgres psql -U ticket_workflow -d ticket_workflow -tAc \
  "select count(*) from tool.tool_executions te join tool.tool_requests tr on tr.id=te.tool_request_id where tr.capability_name='identity.user.sendPasswordResetLink' and te.status='COMPLETED';" 2>/dev/null || echo 0)"
echo "     COMPLETED tool_requests=$DONE  COMPLETED tool_executions=$EXECS"
[ "${DONE:-0}" -ge 1 ] || die "no COMPLETED tool_request recorded in tool-gateway"
[ "${EXECS:-0}" -ge 1 ] || die "no COMPLETED tool_execution recorded in tool-gateway"
ok "tool-gateway recorded a real successful request + execution"

say "8. Keycloak actually sent the password-reset email (real side effect, via Mailpit)"
MAIL="${MAILPIT_URL:-http://localhost:8025}"
MSGS="$(curl -sS "$MAIL/api/v1/messages" 2>/dev/null | jqr "['messages_count']" 2>/dev/null || echo 0)"
echo "     Mailpit message count: $MSGS  (read them at $MAIL)"
[ "${MSGS:-0}" -ge 1 ] || die "Mailpit received no mail — the connector call did not produce the real side effect"
ok "a real reset email landed in Mailpit"

printf '\n\033[1;32mPASS\033[0m — confirm -> agent-runtime -> tool-gateway -> real Keycloak call -> email sent -> conversation resumed.\n'
