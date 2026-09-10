#!/usr/bin/env bash
# OpsMind agent-accuracy evaluation (SPEC-XEVAL-001).
#
# "How accurate is the agent?" — runs the whole evaluation-improvement benchmark
# pipeline against the LIVE agent-runtime and prints the per-dimension scores +
# the release-gate decision.
#
# What it measures (deterministic graders in evaluation-improvement-service, each
# case's ground_truth vs. what the agent actually produced for that user request):
#   CLASSIFICATION_ACCURACY  exact match on the routing decision
#                            (SELF_SERVICE_ACTION / ESCALATED_TO_HUMAN / INFORMATION_PROVIDED)
#   RESOLUTION_SUCCESS       final workflow state matches ground truth AND verification passed
#   TOOL_SELECTION           |called ∩ allowed| / |called|, 0 if any forbidden tool was called
#   POLICY_COMPLIANCE        no policy violations / forbidden-tool calls / unauthorized memory reads
#   HANDOFF_COMPLETENESS     quality-only, UNSCORED unless the dataset carries handoff ground truth
#
# The release gate (`mvp-release-gate-v1`, seeded) requires
#   CLASSIFICATION_ACCURACY >= 0.90  and  TOOL_SELECTION >= 0.95
#   plus every CRITICAL case scored and zero policy violations.
#
# The agent's answers come from whatever CONVERSATION_REASONING_MODE agent-runtime
# is running: `static` -> deterministic keyword routing (free, use this to gate CI on
# routing-logic regressions); `openai`/`anthropic` -> the real model (a few cents,
# non-deterministic, this is the true accuracy number — push it to LangSmith to track
# it over time).
#
# Requires the full platform up. Exits non-zero iff the release gate fails.
#
#   scripts/agent-accuracy-eval.sh [dataset-id]
set -euo pipefail

EVAL_CTR="${EVAL_CONTAINER:-opsmind-evaluation-improvement-service}"
EVAL_URL="${EVAL_URL:-http://localhost:8011}"
DATASET_ID="${1:-${EVAL_DATASET_ID:-10000000-0000-0000-0000-000000000002}}"   # OpsMind IT Support Routing
GATE_POLICY="${EVAL_GATE_POLICY:-mvp-release-gate-v1}"
TARGET_VERSION="${EVAL_TARGET_VERSION:-agent-runtime@$(git rev-parse --short HEAD 2>/dev/null || echo dev)}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m %s\n' "$*"; }
die() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || die "python3 required"
docker inspect "$EVAL_CTR" >/dev/null 2>&1 || die "$EVAL_CTR is not running (bring the full platform up)"

RUN_KEY="accuracy-$(date +%s)-$RANDOM"

say "1. run the benchmark to completion against the live agent"
echo "   dataset       : $DATASET_ID"
echo "   target-version: $TARGET_VERSION"
echo "   gate-policy   : $GATE_POLICY"
echo "   reasoning mode: $(docker exec "$EVAL_CTR" printenv AGENT_RUNTIME_EVALUATION_MODE 2>/dev/null || echo '?') (agent-runtime: $(docker exec opsmind-agent-runtime-service printenv CONVERSATION_REASONING_MODE 2>/dev/null || echo '?'))"

GATE_JSON="$(docker exec "$EVAL_CTR" evaluation-ci-gate \
  --run-key "$RUN_KEY" \
  --dataset-id "$DATASET_ID" \
  --target-version "$TARGET_VERSION" \
  --grader-bundle-version "${EVAL_GRADER_BUNDLE:-grader-bundle-v1}" \
  --policy-version "${EVAL_POLICY_VERSION:-policy-v1}" \
  --gate-policy "$GATE_POLICY" \
  --json 2>&1)" || true
echo "$GATE_JSON" | tail -3 | sed 's/^/   /'

RID="$(printf '%s' "$GATE_JSON" | python3 -c 'import sys,json
for line in sys.stdin:
    line=line.strip()
    if line.startswith("{"):
        try: print(json.loads(line).get("runId","")); break
        except Exception: pass')"
[ -n "$RID" ] || die "could not parse a runId from the gate output:\n$GATE_JSON"
ok "run $RID"

say "2. per-dimension accuracy (mean of the case scores)"
curl -sS "$EVAL_URL/evaluation/runs/$RID/scores" -H "X-Actor-Id: accuracy-eval" -H "X-Actor-Role: create_run" > /tmp/agent-eval-scores.json
# case_key lookup straight from the DB (the /cases REST route needs a role this
# placeholder auth can't assert) — best-effort, purely for readable output.
docker exec "${DB_CONTAINER:-opsmind-postgres}" psql -U ticket_workflow -d ticket_workflow -tAc \
  "select id||'|'||case_key from evaluation.evaluation_test_cases where dataset_id='$DATASET_ID';" 2>/dev/null > /tmp/agent-eval-cases.txt || true
python3 <<'PY'
import json, collections
rows = json.load(open('/tmp/agent-eval-scores.json'))
cases = {}
try:
    for line in open('/tmp/agent-eval-cases.txt'):
        line = line.strip()
        if '|' in line:
            tid, key = line.split('|', 1)
            cases[tid] = key
except FileNotFoundError:
    pass

by_dim = collections.defaultdict(list)
for r in rows:
    by_dim[r['dimension']].append(r)

RELEASE_GATING = ("CLASSIFICATION_ACCURACY", "TOOL_SELECTION")   # mvp-release-gate-v1 thresholds
print(f"   {'dimension':26} {'mean':>6}   scored   note")
wrong = []
for dim in sorted(by_dim):
    scored = [x for x in by_dim[dim] if (x.get('failure_code') or '') != 'UNSCORED']
    if not scored:
        print(f"   {dim:26} {'  -  ':>6}   0/{len(by_dim[dim]):<4}  quality-only (no ground truth in this dataset)")
        continue
    mean = sum(x['score'] for x in scored) / len(scored)
    npass = sum(1 for x in scored if x['passed'])
    note = "  <- release-gate dimension" if dim in RELEASE_GATING else ""
    print(f"   {dim:26} {mean:6.3f}   {npass}/{len(scored):<4}{note}")
    for x in scored:
        if not x['passed']:
            wrong.append((cases.get(x['test_case_id'], x['test_case_id'][:8]), dim, x['score'], x.get('failure_code'), x.get('details') or {}))

if wrong:
    print("\n   cases the agent got wrong:")
    for key, dim, sc, fc, det in wrong:
        print(f"     {key:26} {dim:24} score={sc}  {fc or ''}  {json.dumps(det)[:180]}")
else:
    print("\n   every scored case passed every gating dimension.")
PY

say "3. release-gate decision + LangSmith"
GATE="$(printf '%s' "$GATE_JSON" | python3 -c 'import sys,json
for line in sys.stdin:
    line=line.strip()
    if line.startswith("{"):
        try:
            d=json.loads(line); print(d.get("gateDecision","?"), d.get("runStatus","?"), d.get("passed"))
            break
        except Exception: pass')"
echo "   gateDecision runStatus passed = $GATE"
LS="$(curl -sS "$EVAL_URL/evaluation/runs/$RID/langsmith-link" -H "X-Actor-Id: accuracy-eval" -H "X-Actor-Role: create_run" 2>/dev/null || echo '{}')"
echo "   langsmith: $LS"

case "$GATE" in
  "PASSED "*"True"*) printf '\n\033[1;32mPASS\033[0m — the agent cleared the release gate.\n'; exit 0 ;;
  *)
    printf '\n\033[1;31mGATE %s\033[0m — see the per-case breakdown above (run %s).\n' \
      "$([ "${REPORT_ONLY:-0}" = "1" ] && echo 'not cleared (report-only)' || echo 'FAILED')" "$RID"
    [ "${REPORT_ONLY:-0}" = "1" ] && exit 0 || exit 1
    ;;
esac
