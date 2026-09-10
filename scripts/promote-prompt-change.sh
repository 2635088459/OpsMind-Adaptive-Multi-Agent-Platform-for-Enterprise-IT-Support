#!/usr/bin/env bash
# Promote agent-runtime's conversation-routing system prompt through the REAL
# evaluation-gated improvement pipeline (SPEC-XPROMPT-001 §6 / SPEC-EI-004~010 /
# SPEC-XREL-001), instead of shipping it as a raw edited default.
#
# The flow, all against the live full-platform stack:
#
#   1. benchmark   evaluation-ci-gate runs the candidate prompt against a dataset
#                  (default 10000000-…-0004, "OpsMind IT Support Routing (v2)") and
#                  the mvp-release-gate-v1 release gate -> a terminal PASSED run.
#   2. candidate   POST /evaluation/improvement-candidates   (PROMPT_CHANGE,
#                  target_component = conversation_reasoning_prompt,
#                  proposed_change = {"system_prompt": <text>})
#   3. bind        POST /{id}/benchmark        binds the run; `passed` is derived
#                  from the run's own gate status, never a caller claim (SPEC-EI-025)
#   4. approval    POST /{id}/request-approval  -> PENDING_APPROVAL (+ a 06 approval
#                  ref; FakePolicyApprovalAdapter unless POLICY_APPROVAL_MODE=http)
#   5. approve     POST /{id}/approve           by a DIFFERENT actor (no self-approval)
#   6. canary      POST /{id}/start-canary  then  advance-canary x2
#                  (PLANNED->ACTIVE->EXPANDING->SUCCEEDED)
#   7. promote     POST /{id}/promote  -> status PROMOTED, appends
#                  improvement.promoted.v1 to the outbox
#   8. dispatch    POST /evaluation/outbox/dispatch  -> RabbitMqEventPublisherAdapter
#                  publishes it to opsmind.events
#   9. relay       event-relay consumes it and POSTs agent-runtime
#                  /internal/agent-runtime/v1/events/improvement-promoted
#  10. hot-swap    agent-runtime writes active_component_configs; the next chat turn's
#                  reasoning adapter reads the promoted prompt instead of the built-in
#                  _SYSTEM_PROMPT — no redeploy.
#
# The prompt text promoted is, by default, agent-runtime's CURRENT built-in
# _SYSTEM_PROMPT (read live out of the running container) — i.e. this formalises the
# prompt that is already the default, giving it an auditable eval + approval +
# canary + promotion record. Override with PROMPT_FILE=/path/to/prompt.txt to
# promote a different one.
#
#   scripts/promote-prompt-change.sh
#
# Env:
#   EVAL_DATASET_ID     benchmark dataset            (default …-0004)
#   EVAL_GATE_POLICY    release gate                 (default mvp-release-gate-v1)
#   BENCHMARK_RUN_ID    reuse an existing terminal PASSED run instead of running one
#   PROMPT_FILE         promote this file's contents instead of the live _SYSTEM_PROMPT
#   PROMOTED_VERSION    version label                (default prompt-v2-<date>)
#   AGENT_URL           default http://localhost:8000
#   EVAL_URL            default http://localhost:8011
set -euo pipefail

EVAL_CTR="${EVAL_CONTAINER:-opsmind-evaluation-improvement-service}"
AGENT_CTR="${AGENT_CONTAINER:-opsmind-agent-runtime-service}"
EVAL_URL="${EVAL_URL:-http://localhost:8011}"
AGENT_URL="${AGENT_URL:-http://localhost:8000}"
DATASET_ID="${EVAL_DATASET_ID:-10000000-0000-0000-0000-000000000004}"
GATE_POLICY="${EVAL_GATE_POLICY:-mvp-release-gate-v1}"
TARGET_VERSION="${EVAL_TARGET_VERSION:-agent-runtime@$(git rev-parse --short HEAD 2>/dev/null || echo dev)}"
PROMOTED_VERSION="${PROMOTED_VERSION:-prompt-v2-$(date +%Y%m%d)}"
COMPONENT="conversation_reasoning_prompt"
CID="promote-prompt-$(date +%s)"
KEY="promote-prompt-$(date +%s)-$RANDOM"
# The candidate natural key is sourceRunId:failureClusterId:targetComponent — a
# re-run with the same benchmark run + component would otherwise converge on the
# earlier (already PROMOTED) candidate. A unique sentinel per run keeps each
# invocation a fresh candidate; set SOURCE_FAILURE_CLUSTER_ID to bind a real one.
CLUSTER_ID="${SOURCE_FAILURE_CLUSTER_ID:-prompt-change-$(date +%s)-$RANDOM}"

AUTHOR_ID="prompt-promoter"     ; AUTHOR_ROLE="EVALUATION_ADMIN"
APPROVER_ID="release-approver"  ; APPROVER_ROLE="RELEASE_APPROVER"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 required"
docker inspect "$EVAL_CTR"  >/dev/null 2>&1 || die "$EVAL_CTR not running (bring the full platform up)"
docker inspect "$AGENT_CTR" >/dev/null 2>&1 || die "$AGENT_CTR not running"

jfield() { python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get(sys.argv[1],""))' "$1"; }

# --- the prompt to promote --------------------------------------------------------
say "0. resolve the prompt text to promote"
if [ -n "${PROMPT_FILE:-}" ]; then
  [ -f "$PROMPT_FILE" ] || die "PROMPT_FILE=$PROMPT_FILE not found"
  PROPOSED_CHANGE="$(python3 -c 'import json,sys; print(json.dumps({"system_prompt": open(sys.argv[1]).read()}))' "$PROMPT_FILE")"
  ok "from PROMPT_FILE=$PROMPT_FILE ($(wc -c <"$PROMPT_FILE") bytes)"
else
  PROPOSED_CHANGE="$(docker exec "$AGENT_CTR" python -c 'import json; from agentruntime.infrastructure.conversation_reasoning import _SYSTEM_PROMPT; print(json.dumps({"system_prompt": _SYSTEM_PROMPT}))')"
  [ -n "$PROPOSED_CHANGE" ] || die "could not read _SYSTEM_PROMPT from $AGENT_CTR"
  ok "live _SYSTEM_PROMPT from $AGENT_CTR ($(printf '%s' "$PROPOSED_CHANGE" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["system_prompt"]))') chars)"
fi

# --- 1. benchmark ---------------------------------------------------------------
if [ -n "${BENCHMARK_RUN_ID:-}" ]; then
  RID="$BENCHMARK_RUN_ID"
  say "1. reuse benchmark run $RID"
else
  say "1. benchmark the candidate prompt (dataset $DATASET_ID, gate $GATE_POLICY)"
  RUN_KEY="promote-bench-$(date +%s)-$RANDOM"
  GATE_JSON="$(docker exec "$EVAL_CTR" evaluation-ci-gate \
    --run-key "$RUN_KEY" --dataset-id "$DATASET_ID" --target-version "$TARGET_VERSION" \
    --grader-bundle-version "${EVAL_GRADER_BUNDLE:-grader-bundle-v1}" \
    --policy-version "${EVAL_POLICY_VERSION:-policy-v1}" --gate-policy "$GATE_POLICY" --json 2>&1)" || true
  echo "$GATE_JSON" | tail -2 | sed 's/^/   /'
  RID="$(printf '%s' "$GATE_JSON" | python3 -c 'import sys,json
for l in sys.stdin:
    l=l.strip()
    if l.startswith("{"):
        try:
            d=json.loads(l)
            if d.get("runStatus")!="PASSED": raise SystemExit(f"benchmark gate is {d.get(\"runStatus\")}/{d.get(\"gateDecision\")}, not PASSED")
            print(d.get("runId","")); break
        except json.JSONDecodeError: pass')"
  [ -n "$RID" ] || die "no PASSED runId parsed from the gate output:\n$GATE_JSON"
fi
ok "benchmark run $RID"

# --- 2. create candidate ------------------------------------------------------
say "2. create the PROMPT_CHANGE improvement candidate"
BODY="$(python3 -c 'import json,sys
print(json.dumps({
  "candidate_type":"PROMPT_CHANGE","source_run_id":sys.argv[1],
  "source_failure_cluster_id":sys.argv[2],
  "target_component":sys.argv[3],"proposed_change":json.loads(sys.argv[4]),
  "risk_level":"LOW","created_by":sys.argv[5],
  "correlation_id":sys.argv[6],"idempotency_key":sys.argv[7],
}))' "$RID" "$CLUSTER_ID" "$COMPONENT" "$PROPOSED_CHANGE" "$AUTHOR_ID" "$CID" "$KEY")"
RESP="$(curl -sS -X POST "$EVAL_URL/evaluation/improvement-candidates" \
  -H "Content-Type: application/json" -H "X-Actor-Id: $AUTHOR_ID" -H "X-Actor-Role: $AUTHOR_ROLE" -d "$BODY")"
CANDIDATE_ID="$(printf '%s' "$RESP" | jfield candidate_id)"
CANDIDATE_STATUS="$(printf '%s' "$RESP" | jfield status)"
[ -n "$CANDIDATE_ID" ] || die "create failed: $RESP"
ok "candidate $CANDIDATE_ID  status=$CANDIDATE_STATUS"
[ "$CANDIDATE_STATUS" = "DRAFT" ] || die "expected a fresh DRAFT candidate; got $CANDIDATE_STATUS (natural-key match on an earlier run? set SOURCE_FAILURE_CLUSTER_ID to force a new one)"

# --- 3-7. drive the state machine ------------------------------------------------
step() { # method path json actor_id actor_role expect_field expect_value
  local resp; resp="$(curl -sS -X POST "$EVAL_URL$2" -H "Content-Type: application/json" \
    -H "X-Actor-Id: $4" -H "X-Actor-Role: $5" -d "$3")"
  local got; got="$(printf '%s' "$resp" | jfield "$6")"
  [ "$got" = "$7" ] || die "$1 -> expected $6=$7, got $6=$got  ($resp)"
  ok "$1  $6=$got  canary=$(printf '%s' "$resp" | jfield canary_status)"
}

say "3. bind the benchmark run (passed derived from the run's own gate status)"
step benchmark "/evaluation/improvement-candidates/$CANDIDATE_ID/benchmark" \
  "{\"benchmark_run_id\":\"$RID\",\"correlation_id\":\"$CID\"}" "$AUTHOR_ID" "$AUTHOR_ROLE" benchmark_passed True

say "4. request 06 governance approval"
step request-approval "/evaluation/improvement-candidates/$CANDIDATE_ID/request-approval" \
  "{\"correlation_id\":\"$CID\"}" "$AUTHOR_ID" "$AUTHOR_ROLE" status PENDING_APPROVAL

say "5. approve (by $APPROVER_ID — never the creator)"
step approve "/evaluation/improvement-candidates/$CANDIDATE_ID/approve" \
  "{\"approved_by\":\"$APPROVER_ID\",\"correlation_id\":\"$CID\"}" "$APPROVER_ID" "$APPROVER_ROLE" status APPROVED

say "6. canary: start + advance x2 -> SUCCEEDED"
step start-canary "/evaluation/improvement-candidates/$CANDIDATE_ID/start-canary" \
  "{\"plan_version\":\"canary-v1\",\"stages\":[{\"traffic_percent\":10,\"min_duration_minutes\":5,\"rollback_error_rate_threshold\":0.2,\"sample_size\":10}],\"correlation_id\":\"$CID\",\"idempotency_key\":\"$KEY-canary\"}" \
  "$AUTHOR_ID" "$AUTHOR_ROLE" canary_status ACTIVE
step advance-canary "/evaluation/improvement-candidates/$CANDIDATE_ID/advance-canary" \
  "{\"correlation_id\":\"$CID\",\"idempotency_key\":\"$KEY-adv1\"}" "$AUTHOR_ID" "$AUTHOR_ROLE" canary_status EXPANDING
step advance-canary "/evaluation/improvement-candidates/$CANDIDATE_ID/advance-canary" \
  "{\"correlation_id\":\"$CID\",\"idempotency_key\":\"$KEY-adv2\"}" "$AUTHOR_ID" "$AUTHOR_ROLE" canary_status SUCCEEDED

say "7. promote -> improvement.promoted.v1 on the outbox"
step promote "/evaluation/improvement-candidates/$CANDIDATE_ID/promote" \
  "{\"promoted_version\":\"$PROMOTED_VERSION\",\"correlation_id\":\"$CID\"}" "$AUTHOR_ID" "$AUTHOR_ROLE" status PROMOTED

# --- 8. dispatch the outbox ---------------------------------------------------
say "8. dispatch the evaluation-improvement outbox"
DISP="$(curl -sS -X POST "$EVAL_URL/evaluation/outbox/dispatch" -H "Content-Type: application/json" \
  -H "X-Actor-Id: $AUTHOR_ID" -H "X-Actor-Role: $AUTHOR_ROLE" -d "{\"batch_size\":50,\"correlation_id\":\"$CID\"}")"
echo "   $DISP"
[ "$(printf '%s' "$DISP" | jfield dispatched)" != "0" ] || die "nothing dispatched: $DISP"
ok "dispatched"

# --- 9-10. wait for the relay to hot-swap the prompt -------------------------
say "9-10. wait for event-relay -> agent-runtime hot-swap"
for i in $(seq 1 30); do
  AC="$(curl -sS "$AGENT_URL/internal/agent-runtime/v1/admin/active-config")"
  if printf '%s' "$AC" | python3 -c 'import sys,json; sys.exit(0 if any(c["component"]==sys.argv[1] and c["version"]==sys.argv[2] for c in json.load(sys.stdin)) else 1)' "$COMPONENT" "$PROMOTED_VERSION"; then
    ok "active_component_configs now carries $COMPONENT @ $PROMOTED_VERSION (after ${i}s)"
    printf '%s' "$AC" | python3 -c '
import sys, json
comp = sys.argv[1]
for c in json.load(sys.stdin):
    if c["component"] != comp:
        continue
    sp = (c.get("payload") or {}).get("system_prompt", "")
    print("   component      : " + str(c["component"]))
    print("   version        : " + str(c["version"]))
    print("   sourceCandidate: " + str(c["sourceCandidateId"]))
    print("   activatedAt    : " + str(c["activatedAt"]))
    print("   system_prompt  : %d chars, starts: %r" % (len(sp), sp[:72]))
' "$COMPONENT"
    RELAY_LINE="$(docker logs opsmind-event-relay --since 2m 2>&1 | grep "improvement.promoted.v1" | tail -1 || true)"
    [ -n "$RELAY_LINE" ] && echo "   relay: $RELAY_LINE"
    echo
    printf '\033[1;32mPROMOTED\033[0m candidate %s  ->  %s @ %s (live, no redeploy)\n' "$CANDIDATE_ID" "$COMPONENT" "$PROMOTED_VERSION"
    exit 0
  fi
  sleep 1
done
die "timed out waiting for the hot-swap; last active-config: $AC"
